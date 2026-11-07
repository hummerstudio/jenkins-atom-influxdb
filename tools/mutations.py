#!/usr/bin/env python3
"""Plant one bug at a time in the atom and prove the checks catch it.

A green suite means nothing if it stays green for a broken atom. Each mutation is one textual change to the real
source; the mutated tree is rebuilt and the named case must fail. A surviving mutation is a hole in the suite.

    python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
SOURCE = HERE / "src" / "main" / "java" / "com" / "jenkinschina" / "atom" / "influxdb" / "InfluxDbAtom.java"

MUTATIONS = [
    ("integers are written as floats (no 'i' suffix)",
     "sb.append(((Number) value).longValue()).append('i');",
     "sb.append(((Number) value).longValue());", "field_types_and_order"),
    ("tags come out in descending key order, not the client's sorted order",
     "for (Map.Entry<String, String> tag : new TreeMap<>(tags).entrySet()) {",
     "for (Map.Entry<String, String> tag : new TreeMap<>(tags).descendingMap().entrySet()) {",
     "field_types_and_order"),
    ("a string field is written without escaping its quotes",
     "if (c == '\\\\' || c == '\"') {", "if (c == '\\\\') {", "escaping_roundtrip"),
    ("spaces in the measurement are not escaped",
     "case ' ', ',' -> sb.append('\\\\').append(c);",
     "case ',' -> sb.append('\\\\').append(c);", "escaping_roundtrip"),
    ("UNSTABLE is ordinal 2, so an unstable build is reported failed",
     'ORDINALS.put("UNSTABLE", 1);', 'ORDINALS.put("UNSTABLE", 2);', "unstable_is_successful"),
    ("build_successful only for SUCCESS",
     "fields.put(BUILD_IS_SUCCESSFUL, ordinal < 2);",
     "fields.put(BUILD_IS_SUCCESSFUL, ordinal == 0);", "unstable_is_successful"),
    ("an empty tag value is sent instead of dropped",
     "if (tag.getKey() == null || tag.getKey().isEmpty() || tag.getValue() == null || tag.getValue().isEmpty()) {",
     "if (tag.getKey() == null || tag.getKey().isEmpty()) {", "empty_tag_values_are_dropped"),
    ("auto-detect always picks v1",
     'apiVersion.equals("auto") ? (organization.isEmpty() ? (token.isEmpty() ? "1" : "3") : "2") : apiVersion;',
     'apiVersion.equals("auto") ? "1" : apiVersion;', "auto_detect"),
    ("a custom tag may overwrite project_name",
     "if (RESERVED_TAGS.contains(key)) {", "if (false) {", "reserved_tag_rejected"),
    ("failed + skipped may exceed the total",
     "if (failed + skipped > total) {", "if (false) {", "bad_tests_rejected"),
    ("the point timestamp is milliseconds, not nanoseconds",
     "toLineProtocol(measurement, tags, fields, pointTimestampMs * 1_000_000L);",
     "toLineProtocol(measurement, tags, fields, pointTimestampMs * 1_000L);", "scheduled_timestamp"),
    ("v2 uses a Bearer token instead of InfluxDB's Token scheme",
     'request.header("Authorization", "Token " + token);',
     'request.header("Authorization", "Bearer " + token);', "v2_token"),
    ("a rejected write is swallowed even when failOnError is on",
     "if (!failOnError) {\n                context.log(\"[influxdb] \" + message + \" (failOnError=false, continuing)\");\n                return AtomResult.success(message)\n                        .data(\"written\", \"0\").data(\"apiVersion\", version)\n                        .data(\"endpoint\", uri.getPath()).data(\"httpStatus\", String.valueOf(status))",
     "if (true) {\n                context.log(\"[influxdb] \" + message + \" (failOnError=false, continuing)\");\n                return AtomResult.success(message)\n                        .data(\"written\", \"0\").data(\"apiVersion\", version)\n                        .data(\"endpoint\", uri.getPath()).data(\"httpStatus\", String.valueOf(status))",
     "rejected_write_fails"),
    ("the v2 write path is /write, not /api/v2/write",
     'path = "/api/v2/write";', 'path = "/write";', "v2_token"),
]


def sdk_jar():
    for root in (HERE.parent / "jenkins-atom-sdk", HERE / "jenkins-atom-sdk"):
        candidates = sorted((root / "target").glob("jenkins-atom-sdk-*.jar"))
        if candidates:
            return candidates[-1]
    sys.exit("no SDK jar: build ../jenkins-atom-sdk first")


def build(tree):
    env = dict(os.environ)
    env["SDK_JAR"] = str(sdk_jar())
    # the atom builds with Maven now: package (tests skipped) and take the shaded jar from target/
    proc = subprocess.run(["mvn", "-B", "-q", "-ntp", "-DskipTests", "package"],
                          cwd=tree, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr[-1500:]
    return tree / "target" / "influxdb.jar", proc.stdout


def main():
    failures = 0
    for index, (name, old, new, case) in enumerate(MUTATIONS, 1):
        tree = pathlib.Path(tempfile.mkdtemp(prefix="influxdb-mut-")) / "atom"
        shutil.copytree(HERE, tree, ignore=shutil.ignore_patterns("dist", "target", ".git", "__pycache__"))
        source = tree / SOURCE.relative_to(HERE)
        text = source.read_text()
        if old not in text:
            print("SKIP  %s: the mutation no longer matches the source" % name)
            failures += 1
            continue
        source.write_text(text.replace(old, new, 1))
        jar, output = build(tree)
        if jar is None:
            print("SKIP  %s: the mutated atom does not build: %s" % (name, output))
            failures += 1
            continue
        proc = subprocess.run([sys.executable, "tools/check.py", "run", case, "--jar", str(jar)],
                              cwd=tree, capture_output=True, text=True)
        if proc.returncode == 0:
            print("SURVIVED %-2d %s (case %s stayed green)" % (index, name, case))
            failures += 1
        else:
            print("caught   %-2d %s" % (index, name))
    print("\n%d/%d mutations caught" % (len(MUTATIONS) - failures, len(MUTATIONS)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
