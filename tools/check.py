#!/usr/bin/env python3
"""Behaviour checks for the influxdb atom.

Nothing here trusts the atom. A fake InfluxDB listens on a port and parses the line protocol it receives with its own
parser (escapes, tag/field ordering, the 'i' integer suffix, quoting), checks the auth header and the query parameters
of the version the atom claims to use, and records the request. This script then recomputes, from the atom's own
inputs and from the plugin's rules (JenkinsBasePointGenerator / AbstractPointGenerator / ProjectNameRenderer), what
the point must have said -- project_name, project_namespace, the result ordinal and build_successful, which test
fields must be absent -- and compares that with what the server parsed.

    python3 tools/check.py all --jar dist/influxdb/influxdb.jar
    python3 tools/check.py run v1-basic --jar ...
"""
import argparse
import base64
import http.server
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.parse

ORDINALS = {"SUCCESS": 0, "UNSTABLE": 1, "FAILURE": 2, "NOT_BUILT": 3, "ABORTED": 4}


# ------------------------------------------------------------------ line protocol, parsed independently

def split_unescaped(text, separators):
    """Split on any of `separators` that is not preceded by a backslash."""
    parts, current, i = [], [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            current.append(c)
            current.append(text[i + 1])
            i += 2
            continue
        if c in separators:
            parts.append("".join(current))
            current = []
            if c in " ":
                # the first plain space ends the series key; keep the rest in one piece
                parts.append(text[i + 1:])
                return parts
            i += 1
            continue
        current.append(c)
        i += 1
    parts.append("".join(current))
    return parts


def unescape_key(text):
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            out.append({"n": "\n", "r": "\r", "t": "\t"}.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def split_fields(section):
    parts, current, i = [], [], 0
    quoted = False
    while i < len(section):
        c = section[i]
        if c == "\\" and i + 1 < len(section):
            current.append(section[i:i + 2])
            i += 2
            continue
        if c == '"':
            quoted = not quoted
            current.append(c)
            i += 1
            continue
        if c == "," and not quoted:
            parts.append("".join(current))
            current = []
            i += 1
            continue
        current.append(c)
        i += 1
    parts.append("".join(current))
    return parts


def parse_field_value(raw):
    if raw.startswith('"') and raw.endswith('"') and len(raw) >= 2:
        body = raw[1:-1]
        out, i = [], 0
        while i < len(body):
            if body[i] == "\\" and i + 1 < len(body):
                out.append(body[i + 1])
                i += 2
                continue
            out.append(body[i])
            i += 1
        return "string", "".join(out)
    if raw in ("true", "false"):
        return "boolean", raw == "true"
    if raw.endswith("i") and raw[:-1].lstrip("-").isdigit():
        return "integer", int(raw[:-1])
    try:
        return "float", float(raw)
    except ValueError:
        return "invalid", raw


def parse_line(line):
    """measurement,tags fields timestamp, parsed the way InfluxDB parses it (not by string splitting)."""
    series, remainder = split_unescaped(line, " ")
    remainder = remainder.strip()
    if not remainder:
        raise AssertionError("line has no fields section: %r" % line)
    timestamp = None
    head, _, tail = remainder.rpartition(" ")
    if head and tail.lstrip("-").isdigit():
        remainder, timestamp = head, int(tail)
    segments = split_fields(series)
    measurement = unescape_key(segments[0])
    tags = {}
    for segment in segments[1:]:
        key, _, value = segment.partition("=")
        tags[unescape_key(key)] = unescape_key(value)
    fields = {}
    for segment in split_fields(remainder):
        key, _, raw = segment.partition("=")
        kind, value = parse_field_value(raw)
        fields[unescape_key(key)] = {"type": kind, "value": value}
    return {"measurement": measurement, "tags": tags, "fields": fields,
            "timestamp": timestamp, "raw": line}


# ------------------------------------------------------------------ fake InfluxDB

class Recorder:
    def __init__(self):
        self.requests = []
        self.mode = "ok"
        self.expected = {}
        self.lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        parsed = urllib.parse.urlparse(self.path)
        record = {
            "path": parsed.path,
            "query": dict(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)),
            "headers": {k.lower(): v for k, v in self.headers.items()},
            "body": body,
            "status": None,
        }
        with self.server.state.lock:
            self.server.state.requests.append(record)
        mode = self.server.state.mode
        if mode == "unauthorized":
            record["status"] = 401
            self._reply(401, b'{"code":"unauthorized","message":"unauthorized access"}')
            return
        if mode == "bad-line":
            record["status"] = 400
            self._reply(400, b'{"code":"invalid","message":"unable to parse points"}')
            return
        if mode == "server-error":
            record["status"] = 500
            self._reply(500, b'{"code":"internal error","message":"boom"}')
            return
        status = 200 if parsed.path == "/api/v3/write_lp" else 204
        record["status"] = status
        self._reply(status)


class Server:
    def __init__(self):
        self.state = Recorder()
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.state = self.state
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.port

    def reset(self, mode="ok"):
        with self.state.lock:
            self.state.requests = []
            self.state.mode = mode

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()


# ------------------------------------------------------------------ running the atom

def run_atom(jar, params, secrets=None, env_extra=None):
    jar = os.path.abspath(jar)
    directory = tempfile.mkdtemp(prefix="influxdb-")
    json.dump(params, open(os.path.join(directory, "input.json"), "w"), ensure_ascii=False)
    json.dump(secrets if secrets is not None else {}, open(os.path.join(directory, ".secrets.json"), "w"))
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    env.update({"atom_data_dir": directory, "atom_data_input": "input.json",
                "atom_data_output": "output.json", "atom_workspace": directory})
    java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
    proc = subprocess.run([java, "-cp", jar, "com.jenkinschina.atom.sdk.AtomRunner"], cwd=directory, env=env,
                          capture_output=True, text=True, timeout=180)
    path = os.path.join(directory, "output.json")
    if not os.path.exists(path):
        raise AssertionError("the atom wrote no output.json: %s\n%s" % (proc.returncode, proc.stderr[-2000:]))
    return json.load(open(path)), proc, directory


def data(out, key):
    return (out.get("data", {}).get(key) or {}).get("value", "")


def status(out):
    return out.get("status", "")


# ------------------------------------------------------------------ independent expectations

def expand(value, params, env):
    import re
    def repl(match):
        name = match.group(1)
        return params.get(name, env.get(name, match.group(0)))
    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", repl, value if value is not None else "")


def expected_tags(params, env):
    project_path = expand(params.get("projectPath", "${JOB_NAME}"), params, env).strip()
    name = params.get("projectName", "").strip() or project_path.split("/")[-1]
    prefix = params.get("customPrefix", "").strip()
    project_name = prefix + "_" + name if prefix else name
    result = expand(params.get("buildResult", ""), params, env).strip().upper()
    if not result:
        result = "?"
    tags = {
        "project_name": project_name,
        "project_path": project_path,
        "project_namespace": project_path.split("/")[0],
        "instance": params.get("instance", ""),
        "build_result": result,
    }
    if prefix:
        tags["prefix"] = prefix
    return tags, result


def expected_ordinal(result):
    return 5 if result == "?" else ORDINALS[result]


def check_point(point, params, env, now_ms):
    """Compare one parsed point with what the plugin's generators would have produced from the same inputs."""
    tags, result = expected_tags(params, env)
    assert point["measurement"] == (params.get("measurementName") or "jenkins_data"), point["measurement"]
    for key, value in tags.items():
        assert point["tags"].get(key) == value, "tag %s: %r != %r (tags=%r)" % (key, point["tags"].get(key), value, point["tags"])
    assert set(point["tags"]) == set(tags), "unexpected tags: %r" % (set(point["tags"]) - set(tags))

    fields = point["fields"]
    ordinal = expected_ordinal(result)
    assert fields["build_result_ordinal"]["value"] == ordinal, fields["build_result_ordinal"]
    assert fields["build_successful"]["type"] == "boolean"
    assert fields["build_successful"]["value"] == (ordinal < 2), fields["build_successful"]
    assert fields["build_number"]["value"] == int(expand(params.get("buildNumber", "0"), params, env)), fields["build_number"]

    duration = int(params.get("buildDurationMs") or 0)
    scheduled = int(params.get("buildScheduledTimeMs") or 0)
    if duration > 0:
        assert fields["build_time"]["value"] == duration, fields["build_time"]
    elif scheduled > 0:
        got = fields["build_time"]["value"]
        assert scheduled <= got + 1 and abs(got - (now_ms - scheduled)) < 60000, \
            "build_time %r is not now-start for start %r" % (got, scheduled)

    if params.get("testsTotal"):
        total = int(params["testsTotal"])
        failed = int(params.get("testsFailed") or 0)
        skipped = int(params.get("testsSkipped") or 0)
        assert fields["tests_total"]["value"] == total
        assert fields["tests_failed"]["value"] == failed
        assert fields["tests_skipped"]["value"] == skipped
    else:
        assert not ({"tests_total", "tests_failed", "tests_skipped"} & set(fields)), \
            "test fields without test data: %r" % fields

    if params.get("buildHealth"):
        assert fields["project_build_health"]["value"] == int(params["buildHealth"])
    else:
        assert "project_build_health" not in fields

    for line in (params.get("customFields") or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, raw = line.partition("=")
        raw = expand(raw.strip(), params, env)
        got = fields[key.strip()]
        if raw.lower() in ("true", "false"):
            assert got == {"type": "boolean", "value": raw.lower() == "true"}, got
        elif raw.lstrip("-").isdigit():
            assert got == {"type": "integer", "value": int(raw)}, got
        else:
            assert got == {"type": "string", "value": raw}, got
    return result


def assert_auth(request, version, token=None, user=None, password=None):
    auth = request["headers"].get("authorization")
    if version == "2":
        assert auth == "Token " + token, "Authorization: %r" % auth
    elif version == "3":
        assert auth == "Bearer " + token, "Authorization: %r" % auth
    elif user:
        assert auth and auth.startswith("Basic "), "Authorization: %r" % auth
        decoded = base64.b64decode(auth[6:]).decode()
        assert decoded == user + ":" + password, decoded
    else:
        assert auth is None, "unexpected Authorization: %r" % auth
    assert request["headers"].get("content-type", "").startswith("text/plain"), request["headers"]


def assert_query(request, expected):
    for key, value in expected.items():
        assert request["query"].get(key) == value, "query %s: %r != %r" % (key, request["query"].get(key), value)


# ------------------------------------------------------------------ cases

CASES = {}


def case(fn):
    CASES[fn.__name__] = fn
    return fn


def base(server, **extra):
    params = {
        "url": server.url,
        "database": "jenkins",
        "buildNumber": "42",
        "projectPath": "team/app",
        "buildResult": "SUCCESS",
        "buildStatusMessage": "stable",
        "buildDurationMs": "1234",
        "buildScheduledTimeMs": "1700000000000",
        "agentName": "agent-1",
        "branchName": "main",
        "buildHealth": "87",
        "lastSuccessfulBuild": "41",
        "lastStableBuild": "40",
        "instance": "http://jenkins.example/",
    }
    params.update(extra)
    return params


@case
def v1_basic(server, jar):
    server.reset()
    params = base(server)
    out, proc, _ = run_atom(jar, params)
    assert status(out) == "success", out
    assert len(server.state.requests) == 1, server.state.requests
    request = server.state.requests[0]
    assert request["path"] == "/write"
    assert_query(request, {"db": "jenkins", "precision": "ns"})
    assert_auth(request, "1")
    assert data(out, "written") == "1" and data(out, "apiVersion") == "1"
    point = parse_line(request["body"].strip())
    result = check_point(point, params, os.environ, int(time.time() * 1000))
    assert result == "SUCCESS"
    assert point["fields"]["build_scheduled_time"]["value"] == 1700000000000
    assert point["timestamp"] == int(data(out, "timestamp")), (point["timestamp"], data(out, "timestamp"))


@case
def empty_tag_values_are_dropped(server, jar):
    # the Java client skips a tag whose value is empty (Point#appendTags); instance is often blank on a bare agent
    server.reset()
    params = base(server, instance="")
    out, _, _ = run_atom(jar, params)
    assert status(out) == "success", out
    point = parse_line(server.state.requests[0]["body"].strip())
    assert "instance" not in point["tags"], point["tags"]
    assert point["tags"]["project_name"] == "app"


@case
def v1_retention_and_basic_auth(server, jar):
    server.reset()
    params = base(server, retentionPolicy="thirty_days")
    out, _, _ = run_atom(jar, params, secrets={"username": "metrics", "password": "s3cr3t"})
    assert status(out) == "success", out
    request = server.state.requests[0]
    assert_query(request, {"db": "jenkins", "precision": "ns", "rp": "thirty_days"})
    assert_auth(request, "1", user="metrics", password="s3cr3t")


@case
def v2_token(server, jar):
    server.reset()
    params = base(server, organization="acme", bucket="builds", customPrefix="nightly",
                  projectName="", database="")
    out, _, _ = run_atom(jar, params, secrets={"token": "tok-123"})
    assert status(out) == "success", out
    request = server.state.requests[0]
    assert request["path"] == "/api/v2/write", request["path"]
    assert_query(request, {"org": "acme", "bucket": "builds", "precision": "ns"})
    assert_auth(request, "2", token="tok-123")
    point = parse_line(request["body"].strip())
    check_point(point, params, os.environ, int(time.time() * 1000))
    assert point["tags"]["project_name"] == "nightly_app", point["tags"]
    assert point["tags"]["prefix"] == "nightly"


@case
def v3_bearer(server, jar):
    server.reset()
    params = base(server, database="metrics")
    out, _, _ = run_atom(jar, params, secrets={"token": "tok-v3"})
    assert status(out) == "success", out
    request = server.state.requests[0]
    assert request["path"] == "/api/v3/write_lp", request["path"]
    assert_query(request, {"db": "metrics", "precision": "nanosecond"})
    assert_auth(request, "3", token="tok-v3")
    check_point(parse_line(request["body"].strip()), params, os.environ, int(time.time() * 1000))


@case
def auto_detect(server, jar):
    # org -> v2
    server.reset()
    out, _, _ = run_atom(jar, base(server, organization="acme", bucket="b"), secrets={"token": "t"})
    assert data(out, "apiVersion") == "2", out
    # token, no org -> v3
    server.reset()
    out, _, _ = run_atom(jar, base(server), secrets={"token": "t"})
    assert data(out, "apiVersion") == "3", out
    # neither -> v1
    server.reset()
    out, _, _ = run_atom(jar, base(server))
    assert data(out, "apiVersion") == "1", out


@case
def escaping_roundtrip(server, jar):
    server.reset()
    params = base(server, projectPath="folder/team job, one=x", measurementName="build metrics,one",
                  buildStatusMessage='he said "hi" \\ and left',
                  customTags="env=prod eu\nbranch=a=b\ncomma key=value,here",
                  customFields='note=some "quoted" text\ncount=42')
    out, _, _ = run_atom(jar, params)
    assert status(out) == "success", out
    point = parse_line(server.state.requests[0]["body"].strip())
    assert point["measurement"] == "build metrics,one", point["measurement"]
    assert point["tags"]["project_path"] == "folder/team job, one=x", point["tags"]
    assert point["tags"]["env"] == "prod eu", point["tags"]
    assert point["tags"]["comma key"] == "value,here", point["tags"]
    assert point["tags"]["branch"] == "a=b"
    assert point["fields"]["build_status_message"]["value"] == 'he said "hi" \\ and left'
    assert point["fields"]["note"] == {"type": "string", "value": 'some "quoted" text'}
    assert point["fields"]["count"] == {"type": "integer", "value": 42}
    # raw form still has the documented escapes, sorted keys
    raw = server.state.requests[0]["body"]
    assert "build\\ metrics\\,one," in raw, raw
    # quotes and backslashes inside a string field must be escaped on the wire, not just parse back
    assert 'note="some \\"quoted\\" text"' in raw, raw
    assert 'build_status_message="he said \\"hi\\" \\\\ and left"' in raw, raw
    assert raw.count(" build_agent_name=") == 1


@case
def field_types_and_order(server, jar):
    server.reset()
    params = base(server, customFields="ratio=0.5\nok=false\nbig=9007199254740\nname=plain")
    out, _, _ = run_atom(jar, params)
    raw = server.state.requests[0]["body"].strip()
    assert "ratio=0.5" in raw, raw
    assert "ok=false" in raw, raw
    assert "big=9007199254740i" in raw, raw
    assert 'name="plain"' in raw, raw
    assert "build_number=42i" in raw, raw
    assert "build_successful=true" in raw, raw
    # tags and fields both come out sorted, like the Java client's TreeMap
    tags_part = raw.split(" ")[0].split(",")[1:]
    keys = [segment.split("=")[0] for segment in tags_part]
    assert keys == sorted(keys), keys
    fields_part = raw.split(" ")[1]
    field_keys = [seg.split("=")[0] for seg in fields_part.split(",")]
    assert field_keys == sorted(field_keys), field_keys


@case
def running_build_unknown_result(server, jar):
    server.reset()
    params = base(server, buildResult="", buildDurationMs="")
    out, _, _ = run_atom(jar, params)
    point = parse_line(server.state.requests[0]["body"].strip())
    assert point["tags"]["build_result"] == "?", point["tags"]
    assert point["fields"]["build_result_ordinal"]["value"] == 5
    assert point["fields"]["build_successful"]["value"] is False
    got = point["fields"]["build_time"]["value"]
    assert got >= 0


@case
def unstable_is_successful(server, jar):
    server.reset()
    params = base(server, buildResult="unstable")
    out, _, _ = run_atom(jar, params)
    point = parse_line(server.state.requests[0]["body"].strip())
    assert point["tags"]["build_result"] == "UNSTABLE"
    assert point["fields"]["build_result_ordinal"]["value"] == 1
    assert point["fields"]["build_successful"]["value"] is True


@case
def tests_and_health_omitted(server, jar):
    server.reset()
    params = base(server, buildHealth="", testsTotal="10", testsFailed="2", testsSkipped="1")
    out, _, _ = run_atom(jar, params)
    point = parse_line(server.state.requests[0]["body"].strip())
    assert point["fields"]["tests_total"]["value"] == 10
    assert point["fields"]["tests_failed"]["value"] == 2
    assert point["fields"]["tests_skipped"]["value"] == 1
    assert "project_build_health" not in point["fields"]


@case
def scheduled_timestamp(server, jar):
    server.reset()
    params = base(server, useScheduledTimeAsTimestamp="true", buildScheduledTimeMs="1700000000000")
    out, _, _ = run_atom(jar, params)
    point = parse_line(server.state.requests[0]["body"].strip())
    assert point["timestamp"] == 1700000000000 * 1000000, point["timestamp"]


@case
def env_expansion(server, jar):
    server.reset()
    params = base(server, projectPath="${JOB_NAME}", buildNumber="${BUILD_NUMBER}",
                  customTags="sha=${GIT_COMMIT}")
    out, _, _ = run_atom(jar, params, env_extra={"JOB_NAME": "team/api", "BUILD_NUMBER": "77",
                                                 "GIT_COMMIT": "abc123"})
    point = parse_line(server.state.requests[0]["body"].strip())
    assert point["tags"]["project_path"] == "team/api"
    assert point["tags"]["project_name"] == "api"
    assert point["tags"]["sha"] == "abc123"
    assert point["fields"]["build_number"]["value"] == 77


@case
def missing_token_before_network(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, organization="acme", bucket="b"))
    assert status(out) == "error" and out.get("errorCode") == 40003, out
    assert "token" in out.get("message", "")
    assert server.state.requests == [], "the atom sent a request without a token"


@case
def missing_database(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, database=""))
    assert status(out) == "error" and out.get("errorCode") == 40003, out
    assert server.state.requests == []


@case
def bad_result_rejected(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, buildResult="GREEN"))
    assert status(out) == "error" and out.get("errorCode") == 40006, out
    assert "GREEN" in out.get("message", "")
    assert server.state.requests == []


@case
def bad_tests_rejected(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, testsTotal="3", testsFailed="2", testsSkipped="2"))
    assert status(out) == "error" and out.get("errorCode") == 40007, out
    assert server.state.requests == []


@case
def reserved_tag_rejected(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, customTags="project_name=mine"))
    assert status(out) == "error" and out.get("errorCode") == 40008, out
    assert server.state.requests == []


@case
def rejected_write_fails(server, jar):
    server.reset(mode="unauthorized")
    out, _, _ = run_atom(jar, base(server))
    assert status(out) == "error" and out.get("errorCode") == 40010, out
    assert "401" in out.get("message", "") and "unauthorized" in out.get("message", ""), out
    assert len(server.state.requests) == 1, "the atom retried a rejected write"


@case
def bad_line_keeps_server_body(server, jar):
    server.reset(mode="bad-line")
    out, _, _ = run_atom(jar, base(server))
    assert status(out) == "error" and out.get("errorCode") == 40010, out
    assert "unable to parse points" in out.get("message", ""), out


@case
def server_error_swallowed_when_asked(server, jar):
    server.reset(mode="server-error")
    out, _, _ = run_atom(jar, base(server, failOnError="false"))
    assert status(out) == "success", out
    assert data(out, "written") == "0"
    assert data(out, "httpStatus") == "500"


@case
def connection_refused(server, jar):
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()  # nothing listens there now
    params = base(server, url="http://127.0.0.1:%d" % port, timeoutSeconds="5")
    out, _, _ = run_atom(jar, params)
    assert status(out) == "error" and out.get("errorCode") == 40009, out
    assert "could not reach" in out.get("message", ""), out


@case
def dry_run_sends_nothing(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, dryRun="true"))
    assert status(out) == "success", out
    assert data(out, "written") == "0"
    assert server.state.requests == [], "dry run sent a request"
    point = parse_line(data(out, "lineProtocol").strip())
    check_point(point, base(server), os.environ, int(time.time() * 1000))


@case
def bad_url(server, jar):
    server.reset()
    out, _, _ = run_atom(jar, base(server, url="influxdb:8086"))
    assert status(out) == "error" and out.get("errorCode") == 40001, out
    assert server.state.requests == []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["all", "run", "list"])
    parser.add_argument("name", nargs="?")
    parser.add_argument("--jar", default="dist/influxdb/influxdb.jar")
    args = parser.parse_args()

    if args.command == "list":
        print("\n".join(sorted(CASES)))
        return 0
    if args.command == "all":
        names = sorted(CASES)
    else:
        if args.name not in CASES:
            print("no such case: %s (have %s)" % (args.name, ", ".join(sorted(CASES))))
            return 2
        names = [args.name]

    server = Server()
    failures = 0
    try:
        for name in names:
            started = time.time()
            try:
                CASES[name](server, args.jar)
                print("ok   %-32s %.2fs" % (name, time.time() - started))
            except Exception as exc:
                failures += 1
                print("FAIL %-32s %s" % (name, exc))
                traceback.print_exc()
    finally:
        server.stop()
    print("\n%d/%d passed" % (len(names) - failures, len(names)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
