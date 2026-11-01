package com.jenkinschina.atom.influxdb;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Write this build's metrics to InfluxDB - the post-build half of the influxdb plugin.
 *
 * <p>The plugin does not touch line protocol itself: it builds {@code com.influxdb.client.write.Point}s through the
 * official clients and lets them serialise. The measurement is {@code jenkins_data} (configurable), the tags are
 * project_name / project_path / project_namespace / instance / build_result, and the fields are the build's numbers
 * and strings. This atom builds the same point and serialises it exactly the way the clients do
 * ({@code Point#toLineProtocol}): measurement escaped with '=' left alone, tags and fields in a {@code TreeMap} (so
 * key order is sorted, empty tag values dropped), long and int fields suffixed with 'i', booleans bare, strings
 * double-quoted with backslash escapes, timestamp in nanoseconds.
 *
 * <p>What this atom does differently, because a pipeline should not have to read the plugin's docs to find out why
 * nothing arrived: the target is chosen from the inputs (the plugin's freestyle/global target list does not exist
 * here), and a rejected write fails the step by default ({@code failOnError=true}) instead of being logged and
 * swallowed the way the plugin's {@code exposeExceptions=false} default does.
 */
public class InfluxDbAtom implements AtomTask {

    /** JenkinsBasePointGenerator's constants, same spelling so dashboards keep working. */
    private static final String BUILD_TIME = "build_time";
    private static final String BUILD_STATUS_MESSAGE = "build_status_message";
    private static final String BUILD_SCHEDULED_TIME = "build_scheduled_time";
    private static final String BUILD_EXEC_TIME = "build_exec_time";
    private static final String BUILD_MEASURED_TIME = "build_measured_time";
    private static final String BUILD_RESULT = "build_result";
    private static final String BUILD_RESULT_ORDINAL = "build_result_ordinal";
    private static final String BUILD_IS_SUCCESSFUL = "build_successful";
    private static final String BUILD_AGENT_NAME = "build_agent_name";
    private static final String BUILD_BRANCH_NAME = "build_branch_name";
    private static final String BUILD_USER = "build_user";
    private static final String BUILD_CAUSE = "build_cause";
    private static final String BUILD_CAUSER = "build_causer";
    private static final String PROJECT_BUILD_HEALTH = "project_build_health";
    private static final String PROJECT_LAST_SUCCESSFUL = "last_successful_build";
    private static final String PROJECT_LAST_STABLE = "last_stable_build";
    private static final String TESTS_FAILED = "tests_failed";
    private static final String TESTS_SKIPPED = "tests_skipped";
    private static final String TESTS_TOTAL = "tests_total";

    private static final String PROJECT_NAMESPACE = "project_namespace";
    private static final String PROJECT_NAME = "project_name";
    private static final String PROJECT_PATH = "project_path";
    private static final String INSTANCE = "instance";
    private static final String BUILD_NUMBER = "build_number";
    private static final String CUSTOM_PREFIX = "prefix";

    /** hudson.model.Result order, plus the plugin's ordinal for "no result yet". */
    private static final Map<String, Integer> ORDINALS = new LinkedHashMap<>();

    static {
        ORDINALS.put("SUCCESS", 0);
        ORDINALS.put("UNSTABLE", 1);
        ORDINALS.put("FAILURE", 2);
        ORDINALS.put("NOT_BUILT", 3);
        ORDINALS.put("ABORTED", 4);
    }

    /** Tag names this atom writes itself; a custom tag may not silently replace one. */
    private static final java.util.Set<String> RESERVED_TAGS =
            java.util.Set.of(PROJECT_NAME, PROJECT_PATH, PROJECT_NAMESPACE, INSTANCE, BUILD_RESULT, CUSTOM_PREFIX);

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String rawUrl = expand(context, context.getParam("url", "")).trim();
        String apiVersion = context.getParam("apiVersion", "auto").trim().toLowerCase(Locale.ROOT);
        String organization = expand(context, context.getParam("organization", "")).trim();
        String bucket = expand(context, context.getParam("bucket", "")).trim();
        String database = expand(context, context.getParam("database", "")).trim();
        String retentionPolicy = expand(context, context.getParam("retentionPolicy", "")).trim();
        String measurement = expand(context, context.getParam("measurementName", "jenkins_data")).trim();
        String projectPath = expand(context, context.getParam("projectPath", "${JOB_NAME}")).trim();
        String projectNameParam = expand(context, context.getParam("projectName", "")).trim();
        String prefix = expand(context, context.getParam("customPrefix", "")).trim();
        String instance = expand(context, context.getParam("instance", "")).trim();
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 30), 300));
        boolean dryRun = context.getBooleanParam("dryRun", false);
        boolean failOnError = context.getBooleanParam("failOnError", true);

        String token = firstNonEmpty(context.getSecret("token"), context.getParam("token", ""));
        String username = firstNonEmpty(context.getSecret("username"), context.getParam("username", ""));
        String password = firstNonEmpty(context.getSecret("password"), context.getParam("password", ""));

        if (rawUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "url is required: the InfluxDB base URL, e.g. http://influxdb:8086");
        }
        URI base = parseBase(rawUrl);
        if (base == null) {
            return AtomResult.error(ErrorType.USER, 40001, "url is not an http(s) URL: " + rawUrl);
        }
        if (!apiVersion.equals("auto") && !apiVersion.equals("1") && !apiVersion.equals("2") && !apiVersion.equals("3")) {
            return AtomResult.error(ErrorType.USER, 40002,
                    "apiVersion must be auto, 1, 2 or 3, not '" + apiVersion + "'");
        }
        String version = apiVersion.equals("auto") ? (organization.isEmpty() ? (token.isEmpty() ? "1" : "3") : "2") : apiVersion;

        // Per-version required inputs, checked before anything leaves the machine.
        String db = database.isEmpty() ? bucket : database;
        if (version.equals("1")) {
            if (db.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003, "database is required for the v1 write API");
            }
        } else if (version.equals("2")) {
            if (organization.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003, "organization is required for the v2 write API");
            }
            if (bucket.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003, "bucket is required for the v2 write API");
            }
            if (token.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003,
                        "token is required for the v2 write API (Authorization: Token ...)");
            }
        } else {
            if (db.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003, "database is required for the v3 write API");
            }
            if (token.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40003,
                        "token is required for the v3 write API (Authorization: Bearer ...)");
            }
        }
        if (!password.isEmpty() && username.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "password was given without a username");
        }

        if (measurement.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40004, "measurementName must not be blank");
        }
        if (projectPath.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40004,
                    "projectPath is required: it becomes project_path and project_namespace");
        }

        // ---------------------------------------------------------------- build the point
        String projectName = projectNameParam;
        if (projectName.isEmpty()) {
            String path = projectPath;
            int slash = path.lastIndexOf('/');
            projectName = slash >= 0 ? path.substring(slash + 1) : path;
        }
        projectName = join(prefix, projectName);

        int buildNumber = parseNonNegative(context, "buildNumber",
                expand(context, context.getParam("buildNumber", "${BUILD_NUMBER}")), 40005);
        if (buildNumber < 0) {
            return AtomResult.error(ErrorType.USER, 40005,
                    "buildNumber must be a non-negative integer, not '"
                            + expand(context, context.getParam("buildNumber", "${BUILD_NUMBER}")) + "'");
        }

        String resultParam = context.getParam("buildResult");
        String resultRaw = expand(context, resultParam == null ? "${BUILD_STATUS}" : resultParam).trim()
                .toUpperCase(Locale.ROOT);
        if (resultRaw.contains("${")) {
            // ${BUILD_STATUS} was not set by the runner: no result yet, which the plugin reports as '?'
            resultRaw = "";
        }
        String result;
        int ordinal;
        if (resultRaw.isEmpty() || resultRaw.equals("?")) {
            result = "?";
            ordinal = 5;
        } else if (ORDINALS.containsKey(resultRaw)) {
            result = resultRaw;
            ordinal = ORDINALS.get(resultRaw);
        } else {
            return AtomResult.error(ErrorType.USER, 40006, "buildResult must be one of SUCCESS, UNSTABLE, FAILURE, "
                    + "NOT_BUILT, ABORTED (or empty for a running build), not '" + resultRaw + "'");
        }

        long now = System.currentTimeMillis();
        long timestampMs = parseLong(context, "timestampMs", context.getParam("timestampMs", ""), now, 40005);
        boolean scheduledAsTimestamp = context.getBooleanParam("useScheduledTimeAsTimestamp", false);

        long scheduled = 0L;
        String scheduledParam = expand(context, context.getParam("buildScheduledTimeMs", ""));
        if (!scheduledParam.isEmpty()) {
            scheduled = parseLong(context, "buildScheduledTimeMs", scheduledParam, 0L, 40005);
            if (scheduled < 0) {
                return AtomResult.error(ErrorType.USER, 40005, "buildScheduledTimeMs must be an epoch millisecond value");
            }
        }
        long exec = scheduled;
        String execParam = expand(context, context.getParam("buildExecTimeMs", ""));
        if (!execParam.isEmpty()) {
            exec = parseLong(context, "buildExecTimeMs", execParam, 0L, 40005);
            if (exec < 0) {
                return AtomResult.error(ErrorType.USER, 40005, "buildExecTimeMs must be an epoch millisecond value");
            }
        }
        long duration = 0L;
        String durationParam = expand(context, context.getParam("buildDurationMs", ""));
        if (!durationParam.isEmpty()) {
            duration = parseLong(context, "buildDurationMs", durationParam, 0L, 40005);
            if (duration < 0) {
                return AtomResult.error(ErrorType.USER, 40005, "buildDurationMs must not be negative");
            }
        }
        // The plugin: build.getDuration() == 0 ? now - start : duration (pipeline builds are still running).
        long buildTime = duration > 0 ? duration : (scheduled > 0 ? Math.max(0L, now - scheduled) : 0L);
        long pointTimestampMs = scheduledAsTimestamp && scheduled > 0 ? scheduled : (timestampMs > 0 ? timestampMs : now);

        Map<String, String> tags = new TreeMap<>();
        tags.put(PROJECT_NAME, projectName);
        tags.put(PROJECT_PATH, projectPath);
        tags.put(INSTANCE, instance);
        tags.put(PROJECT_NAMESPACE, projectPath.split("/")[0]);
        tags.put(BUILD_RESULT, result);
        if (!prefix.isEmpty()) {
            tags.put(CUSTOM_PREFIX, prefix);
        }

        Map<String, Object> fields = new TreeMap<>();
        fields.put(BUILD_NUMBER, buildNumber);
        fields.put(BUILD_TIME, buildTime);
        fields.put(BUILD_SCHEDULED_TIME, scheduled > 0 ? scheduled : buildTime);
        fields.put(BUILD_EXEC_TIME, exec > 0 ? exec : (scheduled > 0 ? scheduled : 0L));
        fields.put(BUILD_MEASURED_TIME, now);
        fields.put(BUILD_STATUS_MESSAGE, expand(context, context.getParam("buildStatusMessage", "")).trim());
        fields.put(BUILD_RESULT_ORDINAL, ordinal);
        fields.put(BUILD_IS_SUCCESSFUL, ordinal < 2);
        fields.put(BUILD_AGENT_NAME, expand(context, context.getParam("agentName", "${NODE_NAME}")).trim());
        fields.put(BUILD_BRANCH_NAME, expand(context, context.getParam("branchName", "${BRANCH_NAME}")).trim());
        fields.put(BUILD_CAUSER, expand(context, context.getParam("buildCauser", "")).trim());
        fields.put(BUILD_USER, expand(context, context.getParam("buildUser", "")).trim());
        fields.put(BUILD_CAUSE, expand(context, context.getParam("buildCause", "")).trim());

        long health = parseLong(context, "buildHealth", context.getParam("buildHealth", ""), -1L, 40005);
        if (health >= 0) {
            fields.put(PROJECT_BUILD_HEALTH, (int) Math.min(health, Integer.MAX_VALUE));
        }
        long lastSuccessful = parseLong(context, "lastSuccessfulBuild",
                context.getParam("lastSuccessfulBuild", ""), 0L, 40005);
        fields.put(PROJECT_LAST_SUCCESSFUL, (int) lastSuccessful);
        long lastStable = parseLong(context, "lastStableBuild", context.getParam("lastStableBuild", ""), 0L, 40005);
        fields.put(PROJECT_LAST_STABLE, (int) lastStable);

        String testsTotal = context.getParam("testsTotal", "").trim();
        String testsFailed = context.getParam("testsFailed", "").trim();
        String testsSkipped = context.getParam("testsSkipped", "").trim();
        if (!testsTotal.isEmpty() || !testsFailed.isEmpty() || !testsSkipped.isEmpty()) {
            if (testsTotal.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40007,
                        "testsTotal is required when testsFailed or testsSkipped is given");
            }
            long total = parseLong(context, "testsTotal", testsTotal, -1L, 40007);
            long failed = testsFailed.isEmpty() ? 0L : parseLong(context, "testsFailed", testsFailed, -1L, 40007);
            long skipped = testsSkipped.isEmpty() ? 0L : parseLong(context, "testsSkipped", testsSkipped, -1L, 40007);
            if (total < 0 || failed < 0 || skipped < 0) {
                return AtomResult.error(ErrorType.USER, 40007, "testsTotal/testsFailed/testsSkipped must be integers >= 0");
            }
            if (failed + skipped > total) {
                return AtomResult.error(ErrorType.USER, 40007, "testsFailed + testsSkipped (" + (failed + skipped)
                        + ") is larger than testsTotal (" + total + ")");
            }
            fields.put(TESTS_TOTAL, (int) total);
            fields.put(TESTS_FAILED, (int) failed);
            fields.put(TESTS_SKIPPED, (int) skipped);
        }

        String tagError = mergeProperties(context, tags, context.getParam("customTags", ""), true);
        if (tagError != null) {
            return AtomResult.error(ErrorType.USER, 40008, tagError);
        }
        String fieldError = mergeProperties(context, fields, context.getParam("customFields", ""), false);
        if (fieldError != null) {
            return AtomResult.error(ErrorType.USER, 40008, fieldError);
        }

        String line = toLineProtocol(measurement, tags, fields, pointTimestampMs * 1_000_000L);
        context.log("[influxdb] " + measurement + " project=" + projectName + " build=#" + buildNumber
                + " result=" + result + " (" + ordinal + ") tags=" + tags.size() + " fields=" + fields.size());

        if (dryRun) {
            context.log("[influxdb] dry run, would POST to " + base + ": " + line);
            return AtomResult.success("dry run: built the point but sent nothing")
                    .data("written", "0")
                    .data("apiVersion", version)
                    .data("measurement", measurement)
                    .data("timestamp", String.valueOf(pointTimestampMs * 1_000_000L))
                    .data("lineProtocol", line);
        }

        // ---------------------------------------------------------------- write it
        URI uri = writeUri(base, version, organization, bucket, db, retentionPolicy);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(line + "\n", StandardCharsets.UTF_8));
        if (version.equals("2")) {
            request.header("Authorization", "Token " + token);
        } else if (version.equals("3")) {
            request.header("Authorization", "Bearer " + token);
        } else if (!username.isEmpty()) {
            String basic = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + basic);
        }
        if (!bucket.isEmpty() && !version.equals("2")) {
            request.header("X-Influxdb-Bucket", bucket);
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            String message = "could not reach InfluxDB at " + uri + ": " + e;
            if (!failOnError) {
                context.log("[influxdb] " + message + " (failOnError=false, continuing)");
                return AtomResult.success(message)
                        .data("written", "0").data("apiVersion", version).data("endpoint", uri.getPath())
                        .data("lineProtocol", line);
            }
            return AtomResult.error(ErrorType.THIRD_PART, 40009, message);
        }

        String body = response.body() == null ? "" : response.body().trim();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = "InfluxDB answered HTTP " + status + " for " + uri.getPath()
                    + (body.isEmpty() ? "" : ": " + abbreviate(body));
            if (!failOnError) {
                context.log("[influxdb] " + message + " (failOnError=false, continuing)");
                return AtomResult.success(message)
                        .data("written", "0").data("apiVersion", version)
                        .data("endpoint", uri.getPath()).data("httpStatus", String.valueOf(status))
                        .data("lineProtocol", line);
            }
            return AtomResult.error(ErrorType.THIRD_PART, 40010, message);
        }

        context.log("[influxdb] wrote 1 point to " + uri.getPath() + " (HTTP " + status + ")");
        return AtomResult.success("wrote 1 point to InfluxDB (" + version + " API)")
                .data("written", "1")
                .data("apiVersion", version)
                .data("measurement", measurement)
                .data("endpoint", uri.getPath())
                .data("httpStatus", String.valueOf(status))
                .data("timestamp", String.valueOf(pointTimestampMs * 1_000_000L))
                .data("lineProtocol", line);
    }

    // ------------------------------------------------------------------ line protocol

    /**
     * Exactly {@code com.influxdb.client.write.Point#toLineProtocol}: the measurement escapes spaces, commas and
     * newlines but not '='; tags and fields come out in sorted key order; empty tags are dropped; int and long fields
     * carry the 'i' suffix; booleans are bare; strings are quoted and escape backslash and double quote; the
     * timestamp is nanoseconds.
     */
    static String toLineProtocol(String measurement, Map<String, String> tags, Map<String, Object> fields, long nanos) {
        StringBuilder sb = new StringBuilder();
        escapeKey(sb, measurement, false);
        for (Map.Entry<String, String> tag : new TreeMap<>(tags).entrySet()) {
            if (tag.getKey() == null || tag.getKey().isEmpty() || tag.getValue() == null || tag.getValue().isEmpty()) {
                continue;
            }
            sb.append(',');
            escapeKey(sb, tag.getKey(), true);
            sb.append('=');
            escapeKey(sb, tag.getValue(), true);
        }
        sb.append(' ');
        boolean first = true;
        for (Map.Entry<String, Object> field : new TreeMap<>(fields).entrySet()) {
            Object value = field.getValue();
            if (value == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            escapeKey(sb, field.getKey(), true);
            sb.append('=');
            if (value instanceof Boolean) {
                sb.append(((Boolean) value) ? "true" : "false");
            } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
                sb.append(number(((Number) value).doubleValue()));
            } else if (value instanceof Number) {
                sb.append(((Number) value).longValue()).append('i');
            } else {
                sb.append('"');
                escapeValue(sb, String.valueOf(value));
                sb.append('"');
            }
        }
        sb.append(' ').append(nanos);
        return sb.toString();
    }

    private static void escapeKey(StringBuilder sb, String key, boolean escapeEquals) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ', ',' -> sb.append('\\').append(c);
                case '=' -> {
                    if (escapeEquals) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
                default -> sb.append(c);
            }
        }
    }

    private static void escapeValue(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
    }

    /** The clients format doubles with a DecimalFormat, not Double.toString: 1.0 comes out as {@code 1}. */
    private static String number(double value) {
        DecimalFormat format = new DecimalFormat("#.##########", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(value);
    }

    // ------------------------------------------------------------------ target

    static URI parseBase(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The path each client library posts to; query parameters are the ones the libraries add. */
    static URI writeUri(URI base, String version, String organization, String bucket, String database,
                        String retentionPolicy) {
        String root = base.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        StringBuilder query = new StringBuilder();
        String path;
        if (version.equals("2")) {
            path = "/api/v2/write";
            query.append("org=").append(q(organization));
            query.append("&bucket=").append(q(bucket));
            query.append("&precision=ns");
        } else if (version.equals("3")) {
            path = "/api/v3/write_lp";
            query.append("db=").append(q(bucket.isEmpty() ? database : bucket));
            query.append("&precision=nanosecond");
            if (!retentionPolicy.isEmpty()) {
                query.append("&rp=").append(q(retentionPolicy));
            }
        } else {
            path = "/write";
            query.append("db=").append(q(database));
            query.append("&precision=ns");
            if (!retentionPolicy.isEmpty()) {
                query.append("&rp=").append(q(retentionPolicy));
            }
        }
        return URI.create(root + path + "?" + query);
    }

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------ inputs

    static String expand(AtomContext context, String value) {
        if (value == null || value.indexOf("${") < 0) {
            return value == null ? "" : value;
        }
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = context.getParam(name);
            if (replacement == null) {
                replacement = System.getenv(name);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String join(String prefix, String name) {
        String p = prefix == null ? "" : prefix.trim();
        String n = name == null ? "" : name.trim();
        return p.isEmpty() ? n : n.isEmpty() ? p : p + "_" + n;
    }

    private static int parseNonNegative(AtomContext context, String name, String value, int code) {
        try {
            String trimmed = value == null ? "" : value.trim();
            return trimmed.isEmpty() ? 0 : Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseLong(AtomContext context, String name, String value, long fallback, int code) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Plugin inputs {@code jenkinsEnvParameterTag}/{@code jenkinsEnvParameterField} are java.util.Properties. This takes
     * one {@code key=value} per line instead (comments start with '#'); values expand ${VAR} against the build
     * environment, which is what the plugin's StringSubstitutor does.
     */
    private static String mergeProperties(AtomContext context, Map<String, ?> into, String text, boolean tag) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                return (tag ? "customTags" : "customFields") + " line is not key=value: " + line;
            }
            String key = line.substring(0, equals).trim();
            String value = expand(context, line.substring(equals + 1).trim());
            if (key.isEmpty()) {
                return (tag ? "customTags" : "customFields") + " has an empty key: " + line;
            }
            if (tag) {
                if (RESERVED_TAGS.contains(key)) {
                    return "customTags may not replace the tag '" + key + "' this atom writes";
                }
                ((Map<String, String>) into).put(key, value);
            } else {
                if (key.equals(BUILD_NUMBER) || key.equals(BUILD_MEASURED_TIME)) {
                    return "customFields may not replace the field '" + key + "' this atom writes";
                }
                ((Map<String, Object>) into).put(key, typed(value));
            }
        }
        return null;
    }

    /** Numbers and booleans as such, everything else as a string - the way a typed custom field should land. */
    static Object typed(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.valueOf(value.toLowerCase(Locale.ROOT));
        }
        if (value.matches("-?\\d{1,18}")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (value.matches("-?\\d+\\.\\d+([eE][-+]?\\d+)?") || value.matches("-?\\d+[eE][-+]?\\d+")) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 400 ? oneLine : oneLine.substring(0, 400) + "...";
    }
}
