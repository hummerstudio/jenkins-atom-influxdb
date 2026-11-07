package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The influxdb atom, started the way the runner starts it, against a fake InfluxDB that parses the line protocol with
 * its own parser and checks the auth header and query parameters of the version the atom claims to use.
 *
 * <p>These are the checks the old {@code tools/check.py} made (which {@code tools/smoke.sh} ran): the point is
 * recomputed here from the atom's own inputs and the plugin's rules — project_name, project_namespace, the result
 * ordinal, build_successful, which test fields must be absent — and compared with what the server parsed, never read
 * back out of the atom's output.
 */
class InfluxdbIT {

    private static final Map<String, Integer> ORDINALS = Map.of(
            "SUCCESS", 0, "UNSTABLE", 1, "FAILURE", 2, "NOT_BUILT", 3, "ABORTED", 4);

    private Atom atom;
    private FakeInflux server;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        server = FakeInflux.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------ the write, per API version

    @Test
    @DisplayName("v1: one point to /write, db + ns precision, no auth, and the fields the plugin writes")
    void writesV1() throws Exception {
        server.reset();
        Map<String, String> params = base();
        AtomResult run = run(params, null);

        run.assertOk();
        assertEquals("success", run.status(), run.file("output.json"));
        assertEquals(1, server.requests().size(), String.valueOf(server.requests()));
        Map<String, Object> request = server.requests().get(0);

        assertEquals("/write", request.get("path"));
        assertQuery(request, Map.of("db", "jenkins", "precision", "ns"));
        assertAuth(request, "1", null, null);
        assertEquals("1", run.field("written"));
        assertEquals("1", run.field("apiVersion"));

        Point point = parseLine(String.valueOf(request.get("body")).strip());
        assertEquals("SUCCESS", checkPoint(point, params, System.currentTimeMillis()));
        assertEquals(1700000000000L, number(point.field("build_scheduled_time")));
        assertEquals(Long.parseLong(run.field("timestamp")), point.timestamp().longValue());
    }

    @Test
    @DisplayName("an empty tag value is dropped, the way the Java client drops it")
    void dropsEmptyTagValues() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("instance", "");
        AtomResult run = run(params, null);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertFalse(point.tags().containsKey("instance"), "tags: " + point.tags());
        assertEquals("app", point.tags().get("project_name"));
    }

    @Test
    @DisplayName("v1: a retention policy is a query parameter and basic auth carries the user and password")
    void writesV1WithRetentionAndBasicAuth() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("retentionPolicy", "thirty_days");
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("username", "metrics");
        secrets.put("password", "s3cr3t");
        AtomResult run = run(params, secrets);

        run.assertOk();
        Map<String, Object> request = server.requests().get(0);
        assertQuery(request, Map.of("db", "jenkins", "precision", "ns", "rp", "thirty_days"));
        assertAuth(request, "1", "metrics", "s3cr3t");
    }

    @Test
    @DisplayName("v2: org + bucket go to /api/v2/write with a Token header, and the prefix is in project_name")
    void writesV2() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("organization", "acme");
        params.put("bucket", "builds");
        params.put("customPrefix", "nightly");
        params.put("projectName", "");
        params.put("database", "");
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("token", "tok-123");
        AtomResult run = run(params, secrets);

        run.assertOk();
        Map<String, Object> request = server.requests().get(0);
        assertEquals("/api/v2/write", request.get("path"));
        assertQuery(request, Map.of("org", "acme", "bucket", "builds", "precision", "ns"));
        assertAuth(request, "2", null, null, "tok-123");

        Point point = parseLine(String.valueOf(request.get("body")).strip());
        checkPoint(point, params, System.currentTimeMillis());
        assertEquals("nightly_app", point.tags().get("project_name"));
        assertEquals("nightly", point.tags().get("prefix"));
    }

    @Test
    @DisplayName("v3: database goes to /api/v3/write_lp with a Bearer header and nanosecond precision")
    void writesV3() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("database", "metrics");
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("token", "tok-v3");
        AtomResult run = run(params, secrets);

        run.assertOk();
        Map<String, Object> request = server.requests().get(0);
        assertEquals("/api/v3/write_lp", request.get("path"));
        assertQuery(request, Map.of("db", "metrics", "precision", "nanosecond"));
        assertAuth(request, "3", null, null, "tok-v3");
        checkPoint(parseLine(String.valueOf(request.get("body")).strip()), params, System.currentTimeMillis());
    }

    @Test
    @DisplayName("the version is auto-detected: org means 2, a token alone means 3, neither means 1")
    void detectsTheApiVersion() throws Exception {
        server.reset();
        Map<String, String> withOrg = base();
        withOrg.put("organization", "acme");
        withOrg.put("bucket", "b");
        Map<String, String> token = new LinkedHashMap<>();
        token.put("token", "t");
        assertEquals("2", run(withOrg, token).field("apiVersion"));

        server.reset();
        assertEquals("3", run(base(), token).field("apiVersion"));

        server.reset();
        assertEquals("1", run(base(), null).field("apiVersion"));
    }

    @Test
    @DisplayName("escapes survive the round trip: measurement, tag values, string fields, and the wire form")
    void escapesTheLineProtocol() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("projectPath", "folder/team job, one=x");
        params.put("measurementName", "build metrics,one");
        params.put("buildStatusMessage", "he said \"hi\" \\ and left");
        params.put("customTags", "env=prod eu\nbranch=a=b\ncomma key=value,here");
        params.put("customFields", "note=some \"quoted\" text\ncount=42");
        AtomResult run = run(params, null);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertEquals("build metrics,one", point.measurement());
        assertEquals("folder/team job, one=x", point.tags().get("project_path"));
        assertEquals("prod eu", point.tags().get("env"));
        assertEquals("value,here", point.tags().get("comma key"));
        assertEquals("a=b", point.tags().get("branch"));
        assertEquals("he said \"hi\" \\ and left", point.field("build_status_message").value());
        assertEquals("string", point.field("note").kind());
        assertEquals("some \"quoted\" text", point.field("note").value());
        assertEquals("integer", point.field("count").kind());
        assertEquals(42L, number(point.field("count")));

        String raw = String.valueOf(server.requests().get(0).get("body"));
        assertTrue(raw.contains("build\\ metrics\\,one,"), "raw: " + raw);
        assertTrue(raw.contains("note=\"some \\\"quoted\\\" text\""), "raw: " + raw);
        assertTrue(raw.contains("build_status_message=\"he said \\\"hi\\\" \\\\ and left\""), "raw: " + raw);
        assertEquals(1, count(raw, " build_agent_name="), "a field must appear once: " + raw);
    }

    @Test
    @DisplayName("field types are written the way the client writes them, and keys come out sorted")
    void writesFieldTypesAndOrder() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("customFields", "ratio=0.5\nok=false\nbig=9007199254740\nname=plain");
        AtomResult run = run(params, null);

        run.assertOk();
        String raw = String.valueOf(server.requests().get(0).get("body")).strip();
        assertTrue(raw.contains("ratio=0.5"), raw);
        assertTrue(raw.contains("ok=false"), raw);
        assertTrue(raw.contains("big=9007199254740i"), raw);
        assertTrue(raw.contains("name=\"plain\""), raw);
        assertTrue(raw.contains("build_number=42i"), raw);
        assertTrue(raw.contains("build_successful=true"), raw);

        List<String> tagsPart = List.of(raw.split(" ")[0].split(","));
        List<String> keys = new ArrayList<>();
        for (int i = 1; i < tagsPart.size(); i++) {
            keys.add(tagsPart.get(i).split("=")[0]);
        }
        assertEquals(sorted(keys), keys, "tags come out sorted: " + keys);

        List<String> fieldKeys = new ArrayList<>();
        for (String segment : raw.split(" ")[1].split(",")) {
            fieldKeys.add(segment.split("=")[0]);
        }
        assertEquals(sorted(fieldKeys), fieldKeys, "fields come out sorted: " + fieldKeys);
    }

    @Test
    @DisplayName("a running build has no result yet: ? maps to ordinal 5 and build_successful=false")
    void writesAnUnknownResult() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("buildResult", "");
        params.put("buildDurationMs", "");
        AtomResult run = run(params, null);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertEquals("?", point.tags().get("build_result"));
        assertEquals(5L, number(point.field("build_result_ordinal")));
        assertEquals(false, point.field("build_successful").value());
        assertTrue(number(point.field("build_time")) >= 0);
    }

    @Test
    @DisplayName("UNSTABLE is a successful build with ordinal 1")
    void treatsUnstableAsSuccessful() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("buildResult", "unstable");
        AtomResult run = run(params, null);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertEquals("UNSTABLE", point.tags().get("build_result"));
        assertEquals(1L, number(point.field("build_result_ordinal")));
        assertEquals(true, point.field("build_successful").value());
    }

    @Test
    @DisplayName("test counts are written when given, and the build health is omitted when it is blank")
    void writesTestsAndOmitsABlankHealth() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("buildHealth", "");
        params.put("testsTotal", "10");
        params.put("testsFailed", "2");
        params.put("testsSkipped", "1");
        AtomResult run = run(params, null);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertEquals(10L, number(point.field("tests_total")));
        assertEquals(2L, number(point.field("tests_failed")));
        assertEquals(1L, number(point.field("tests_skipped")));
        assertFalse(point.fields().containsKey("project_build_health"), "fields: " + point.fields());
    }

    @Test
    @DisplayName("useScheduledTimeAsTimestamp puts the scheduled millis in the point's timestamp")
    void writesTheScheduledTimestamp() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("useScheduledTimeAsTimestamp", "true");
        params.put("buildScheduledTimeMs", "1700000000000");
        AtomResult run = run(params, null);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertEquals(1700000000000L * 1000000L, point.timestamp().longValue());
    }

    @Test
    @DisplayName("${ENV} in the inputs expands from the build environment")
    void expandsTheEnvironment() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("projectPath", "${JOB_NAME}");
        params.put("buildNumber", "${BUILD_NUMBER}");
        params.put("customTags", "sha=${GIT_COMMIT}");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JOB_NAME", "team/api");
        env.put("BUILD_NUMBER", "77");
        env.put("GIT_COMMIT", "abc123");
        AtomResult run = run(params, null, env);

        run.assertOk();
        Point point = parseLine(String.valueOf(server.requests().get(0).get("body")).strip());
        assertEquals("team/api", point.tags().get("project_path"));
        assertEquals("api", point.tags().get("project_name"));
        assertEquals("abc123", point.tags().get("sha"));
        assertEquals(77L, number(point.field("build_number")));
    }

    @Test
    @DisplayName("dryRun keeps the line protocol and sends nothing")
    void sendsNothingOnADryRun() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("dryRun", "true");
        AtomResult run = run(params, null);

        run.assertOk();
        assertEquals("0", run.field("written"));
        assertEquals(List.of(), server.requests(), "dry run sent a request");
        checkPoint(parseLine(run.field("lineProtocol").strip()), base(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ refusals before the network

    @Test
    @DisplayName("v2 without a token is refused before anything is sent")
    void refusesWithoutAToken() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("organization", "acme");
        params.put("bucket", "b");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40003", run.field("errorCode"));
        assertTrue(run.document().toString().contains("token"), "the message must name the token: "
                + run.file("output.json"));
        assertEquals(List.of(), server.requests(), "the atom sent a request without a token");
    }

    @Test
    @DisplayName("v1 without a database is refused before anything is sent")
    void refusesWithoutADatabase() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("database", "");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40003", run.field("errorCode"));
        assertEquals(List.of(), server.requests());
    }

    @Test
    @DisplayName("a result outside the plugin's five is refused by name")
    void refusesAnUnknownResult() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("buildResult", "GREEN");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40006", run.field("errorCode"));
        assertTrue(String.valueOf(run.document().get("message")).contains("GREEN"));
        assertEquals(List.of(), server.requests());
    }

    @Test
    @DisplayName("failed + skipped larger than the total is refused")
    void refusesImpossibleTestCounts() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("testsTotal", "3");
        params.put("testsFailed", "2");
        params.put("testsSkipped", "2");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40007", run.field("errorCode"));
        assertEquals(List.of(), server.requests());
    }

    @Test
    @DisplayName("a custom tag may not replace one the atom writes itself")
    void refusesAReservedTag() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("customTags", "project_name=mine");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40008", run.field("errorCode"));
        assertEquals(List.of(), server.requests());
    }

    @Test
    @DisplayName("a url without a scheme is refused, and nothing is sent")
    void refusesABadUrl() throws Exception {
        server.reset();
        Map<String, String> params = base();
        params.put("url", "influxdb:8086");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40001", run.field("errorCode"));
        assertEquals(List.of(), server.requests());
    }

    // ------------------------------------------------------------------ what the server answers

    @Test
    @DisplayName("a 401 fails the step, keeps the server's words, and is not retried")
    void reportsARejectedWrite() throws Exception {
        server.reset("unauthorized");
        AtomResult run = run(base(), null);

        run.assertRefused();
        assertEquals("40010", run.field("errorCode"));
        String message = String.valueOf(run.document().get("message"));
        assertTrue(message.contains("401") && message.contains("unauthorized"), "message: " + message);
        assertEquals(1, server.requests().size(), "the atom retried a rejected write");
    }

    @Test
    @DisplayName("a 400 keeps the server's body, which is where the parse error is")
    void keepsTheServerBody() throws Exception {
        server.reset("bad-line");
        AtomResult run = run(base(), null);

        run.assertRefused();
        assertEquals("40010", run.field("errorCode"));
        assertTrue(String.valueOf(run.document().get("message")).contains("unable to parse points"),
                "message: " + run.document().get("message"));
    }

    @Test
    @DisplayName("with failOnError=false a 500 is reported and swallowed")
    void swallowsAServerErrorWhenAsked() throws Exception {
        server.reset("server-error");
        Map<String, String> params = base();
        params.put("failOnError", "false");
        AtomResult run = run(params, null);

        run.assertOk();
        assertEquals("0", run.field("written"));
        assertEquals("500", run.field("httpStatus"));
    }

    @Test
    @DisplayName("nothing listening is an environment error that says so")
    void reportsAConnectionRefused() throws Exception {
        int dead;
        try (ServerSocket socket = new ServerSocket(0)) {
            dead = socket.getLocalPort();
        }
        Map<String, String> params = base();
        params.put("url", "http://127.0.0.1:" + dead);
        params.put("timeoutSeconds", "5");
        AtomResult run = run(params, null);

        run.assertRefused();
        assertEquals("40009", run.field("errorCode"));
        assertTrue(String.valueOf(run.document().get("message")).contains("could not reach"),
                "message: " + run.document().get("message"));
    }

    // ------------------------------------------------------------------ independent expectations

    /** Compare one parsed point with what the plugin's generators would have produced from the same inputs. */
    private String checkPoint(Point point, Map<String, String> params, long nowMs) {
        String projectPath = expand(params.getOrDefault("projectPath", "${JOB_NAME}"), params).strip();
        String name = params.getOrDefault("projectName", "").strip();
        if (name.isEmpty()) {
            String[] segments = projectPath.split("/");
            name = segments[segments.length - 1];
        }
        String prefix = params.getOrDefault("customPrefix", "").strip();
        String projectName = prefix.isEmpty() ? name : prefix + "_" + name;
        String result = expand(params.getOrDefault("buildResult", ""), params).strip().toUpperCase(Locale.ROOT);
        if (result.isEmpty()) {
            result = "?";
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("project_name", projectName);
        tags.put("project_path", projectPath);
        tags.put("project_namespace", projectPath.split("/")[0]);
        tags.put("instance", params.getOrDefault("instance", ""));
        tags.put("build_result", result);
        if (!prefix.isEmpty()) {
            tags.put("prefix", prefix);
        }

        String measurement = params.getOrDefault("measurementName", "");
        assertEquals(measurement.isEmpty() ? "jenkins_data" : measurement, point.measurement(), "measurement");
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            if (tag.getValue().isEmpty()) {
                assertFalse(point.tags().containsKey(tag.getKey()), "tag " + tag.getKey() + " should be dropped");
                continue;
            }
            assertEquals(tag.getValue(), point.tags().get(tag.getKey()), "tag " + tag.getKey()
                    + " (tags=" + point.tags() + ")");
        }
        Set<String> expectedKeys = new LinkedHashSet<>(tags.keySet());
        expectedKeys.removeIf(key -> tags.get(key).isEmpty());
        assertEquals(expectedKeys, point.tags().keySet(), "unexpected tags: " + point.tags());

        Map<String, Field> fields = point.fields();
        long ordinal = "?".equals(result) ? 5 : ORDINALS.get(result);
        assertEquals(ordinal, number(fields.get("build_result_ordinal")), "build_result_ordinal");
        assertEquals("boolean", fields.get("build_successful").kind());
        assertEquals(ordinal < 2, fields.get("build_successful").value(), "build_successful");
        assertEquals(Long.parseLong(expand(params.getOrDefault("buildNumber", "0"), params).strip()),
                number(fields.get("build_number")), "build_number");

        long duration = numberOrZero(params.get("buildDurationMs"));
        long scheduled = numberOrZero(params.get("buildScheduledTimeMs"));
        if (duration > 0) {
            assertEquals(duration, number(fields.get("build_time")), "build_time");
        } else if (scheduled > 0) {
            long got = number(fields.get("build_time"));
            assertTrue(scheduled <= got + 1 && Math.abs(got - (nowMs - scheduled)) < 60000,
                    "build_time " + got + " is not now-start for start " + scheduled);
        }

        String testsTotal = params.getOrDefault("testsTotal", "");
        if (!testsTotal.isEmpty()) {
            assertEquals(Long.parseLong(testsTotal), number(fields.get("tests_total")));
            assertEquals(numberOrZero(params.getOrDefault("testsFailed", "")), number(fields.get("tests_failed")));
            assertEquals(numberOrZero(params.getOrDefault("testsSkipped", "")), number(fields.get("tests_skipped")));
        } else {
            for (String key : List.of("tests_total", "tests_failed", "tests_skipped")) {
                assertFalse(fields.containsKey(key), "test fields without test data: " + fields.keySet());
            }
        }

        String health = params.getOrDefault("buildHealth", "");
        if (health.isEmpty()) {
            assertFalse(fields.containsKey("project_build_health"), "fields: " + fields.keySet());
        } else {
            assertEquals(Long.parseLong(health), number(fields.get("project_build_health")));
        }

        for (String line : params.getOrDefault("customFields", "").split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            String key = trimmed.substring(0, equals).strip();
            String raw = expand(trimmed.substring(equals + 1).strip(), params);
            Field got = fields.get(key);
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                assertEquals("boolean", got.kind(), key);
                assertEquals(Boolean.parseBoolean(raw.toLowerCase(Locale.ROOT)), got.value(), key);
            } else if (raw.replaceFirst("^-", "").chars().allMatch(Character::isDigit)) {
                assertEquals("integer", got.kind(), key);
                assertEquals(Long.parseLong(raw), number(got), key);
            } else {
                assertEquals("string", got.kind(), key);
                assertEquals(raw, got.value(), key);
            }
        }
        return result;
    }

    private void assertAuth(Map<String, Object> request, String version, String user, String password)
            throws Exception {
        assertAuth(request, version, user, password, null);
    }

    private void assertAuth(Map<String, Object> request, String version, String user, String password, String token) {
        Map<String, String> headers = headers(request);
        String auth = headers.get("authorization");
        switch (version) {
            case "2" -> assertEquals("Token " + token, auth);
            case "3" -> assertEquals("Bearer " + token, auth);
            default -> {
                if (user != null) {
                    assertTrue(auth != null && auth.startsWith("Basic "), "expected basic auth, got " + auth);
                    String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                    assertEquals(user + ":" + password, decoded);
                } else {
                    assertNull(auth, "unexpected Authorization header");
                }
            }
        }
        assertTrue(headers.getOrDefault("content-type", "").startsWith("text/plain"),
                "content-type: " + headers.get("content-type"));
    }

    private static void assertQuery(Map<String, Object> request, Map<String, String> expected) {
        @SuppressWarnings("unchecked")
        Map<String, String> query = (Map<String, String>) request.get("query");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), query.get(entry.getKey()),
                    "query " + entry.getKey() + " (query=" + query + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Map<String, Object> request) {
        return (Map<String, String>) request.get("headers");
    }

    /** The inputs the check script used, with the fake's URL. */
    private Map<String, String> base() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", server.url());
        params.put("database", "jenkins");
        params.put("buildNumber", "42");
        params.put("projectPath", "team/app");
        params.put("buildResult", "SUCCESS");
        params.put("buildStatusMessage", "stable");
        params.put("buildDurationMs", "1234");
        params.put("buildScheduledTimeMs", "1700000000000");
        params.put("agentName", "agent-1");
        params.put("branchName", "main");
        params.put("buildHealth", "87");
        params.put("lastSuccessfulBuild", "41");
        params.put("lastStableBuild", "40");
        params.put("instance", "http://jenkins.example/");
        return params;
    }

    private AtomResult run(Map<String, String> params, Map<String, String> secrets) throws Exception {
        return run(params, secrets, Map.of());
    }

    private AtomResult run(Map<String, String> params, Map<String, String> secrets, Map<String, String> env)
            throws Exception {
        // a fresh harness per run: the case-level inputs of one run must not leak into the next
        Atom prepared = Atom.of().file(".secrets.json", Json.write(secrets == null ? Map.of() : secrets));
        for (Map.Entry<String, String> param : params.entrySet()) {
            prepared = prepared.input(param.getKey(), param.getValue());
        }
        for (Map.Entry<String, String> variable : env.entrySet()) {
            prepared = prepared.env(variable.getKey(), variable.getValue());
        }
        return prepared.run();
    }

    private static String expand(String value, Map<String, String> params) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
                .matcher(value == null ? "" : value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = params.get(name);
            if (replacement == null) {
                replacement = System.getenv(name);
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static long numberOrZero(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long number(Field field) {
        Object value = field.value();
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            total++;
        }
        return total;
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }

    // ------------------------------------------------------------------ line protocol, parsed independently

    record Field(String kind, Object value) {
    }

    record Point(String measurement, Map<String, String> tags, Map<String, Field> fields, Long timestamp,
            String raw) {
        Field field(String name) {
            return fields.get(name);
        }
    }

    /** Split on any of {@code separators} that is not preceded by a backslash; the first plain space ends the key. */
    private static List<String> splitUnescaped(String text, String separators) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                current.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (separators.indexOf(c) >= 0) {
                parts.add(current.toString());
                current.setLength(0);
                if (c == ' ') {
                    parts.add(text.substring(i + 1));
                    return parts;
                }
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unescapeKey(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static List<String> splitFields(String section) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < section.length()) {
            char c = section.charAt(i);
            if (c == '\\' && i + 1 < section.length()) {
                current.append(section, i, i + 2);
                i += 2;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
                i++;
                continue;
            }
            if (c == ',' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        parts.add(current.toString());
        return parts;
    }

    private static Field parseFieldValue(String raw) {
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            String body = raw.substring(1, raw.length() - 1);
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < body.length()) {
                if (body.charAt(i) == '\\' && i + 1 < body.length()) {
                    out.append(body.charAt(i + 1));
                    i += 2;
                    continue;
                }
                out.append(body.charAt(i));
                i++;
            }
            return new Field("string", out.toString());
        }
        if (raw.equals("true") || raw.equals("false")) {
            return new Field("boolean", Boolean.parseBoolean(raw));
        }
        if (raw.endsWith("i")) {
            String digits = raw.substring(0, raw.length() - 1);
            if (!digits.isEmpty() && digits.replaceFirst("^-", "").chars().allMatch(Character::isDigit)) {
                return new Field("integer", Long.parseLong(digits));
            }
        }
        try {
            return new Field("float", Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return new Field("invalid", raw);
        }
    }

    /** measurement,tags fields timestamp — parsed the way InfluxDB parses it, not by string splitting. */
    private static Point parseLine(String line) {
        List<String> seriesParts = splitUnescaped(line, " ");
        String series = seriesParts.get(0);
        String remainder = seriesParts.size() > 1 ? seriesParts.get(1).strip() : "";
        assertFalse(remainder.isEmpty(), "line has no fields section: " + line);

        Long timestamp = null;
        int lastSpace = remainder.lastIndexOf(' ');
        if (lastSpace > 0) {
            String tail = remainder.substring(lastSpace + 1);
            if (!tail.isEmpty() && tail.replaceFirst("^-", "").chars().allMatch(Character::isDigit)) {
                timestamp = Long.parseLong(tail);
                remainder = remainder.substring(0, lastSpace);
            }
        }

        List<String> segments = splitFields(series);
        String measurement = unescapeKey(segments.get(0));
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; i < segments.size(); i++) {
            String segment = segments.get(i);
            int equals = segment.indexOf('=');
            tags.put(unescapeKey(segment.substring(0, equals)), unescapeKey(segment.substring(equals + 1)));
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String segment : splitFields(remainder)) {
            int equals = segment.indexOf('=');
            fields.put(unescapeKey(segment.substring(0, equals)), parseFieldValue(segment.substring(equals + 1)));
        }
        return new Point(measurement, tags, fields, timestamp, line);
    }

    // ------------------------------------------------------------------ the fake InfluxDB

    private static final class FakeInflux implements AutoCloseable {

        private final HttpServer server;
        private final List<Map<String, Object>> requests =
                Collections.synchronizedList(new ArrayList<>());
        private String mode = "ok";

        private FakeInflux(HttpServer server) {
            this.server = server;
        }

        static FakeInflux start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeInflux fake = new FakeInflux(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            reset("ok");
        }

        void reset(String mode) {
            synchronized (requests) {
                requests.clear();
            }
            this.mode = mode;
        }

        List<Map<String, Object>> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("path", exchange.getRequestURI().getPath());
            record.put("query", parseQuery(exchange.getRequestURI().getRawQuery()));
            record.put("headers", headers);
            record.put("body", body);
            synchronized (requests) {
                requests.add(record);
            }

            switch (mode) {
                case "unauthorized" -> respond(exchange, 401,
                        "{\"code\":\"unauthorized\",\"message\":\"unauthorized access\"}");
                case "bad-line" -> respond(exchange, 400, "{\"code\":\"invalid\",\"message\":\"unable to parse points\"}");
                case "server-error" -> respond(exchange, 500, "{\"code\":\"internal error\",\"message\":\"boom\"}");
                default -> {
                    int status = "/api/v3/write_lp".equals(exchange.getRequestURI().getPath()) ? 200 : 204;
                    if (status == 204) {
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                    } else {
                        respond(exchange, 200, "");
                    }
                }
            }
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private static Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> query = new LinkedHashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return query;
            }
            for (String pair : rawQuery.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                query.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
            return query;
        }
    }
}
