# influxdb

Writes this build's metrics to InfluxDB - the post-build half of the
[influxdb](https://plugins.jenkins.io/influxdb/) plugin (`influxDbPublisher`), version 6.1.

## What the plugin actually does

The plugin never writes line protocol itself. `InfluxDbPublicationService` collects `AbstractPoint`s from a dozen
generators and hands them to the official client (`InfluxDBClientWrapper`), which serialises them. The build point is
`JenkinsBasePointGenerator` under `AbstractPointGenerator`:

- measurement `jenkins_data` (configurable; `measurementName` replaces it everywhere);
- tags: `project_name` (prefix and project joined with `_`), `project_path` (the job's full name), `project_namespace`
  (first path segment), `instance` (Jenkins root URL), `build_result`, and `prefix` when a custom prefix is set;
- fields: `build_number`, `build_time` (duration, or *now - start* for a pipeline that is still running),
  `build_scheduled_time`, `build_exec_time`, `build_measured_time`, `build_status_message`, `build_result_ordinal`,
  `build_successful` (`ordinal < 2`), `build_agent_name`, `build_branch_name`, `project_build_health`,
  `last_successful_build`, `last_stable_build`, `build_causer`, `build_user`, `build_cause`, and
  `tests_total`/`tests_failed`/`tests_skipped` only when the build has test results;
- result ordinal follows `hudson.model.Result`: SUCCESS 0, UNSTABLE 1, FAILURE 2, NOT_BUILT 3, ABORTED 4; a run with no
  result yet is the tag `?` and ordinal 5 (that is the plugin's own choice, not an invention here).

The wire format is `com.influxdb.client.write.Point#toLineProtocol`, which this atom reproduces rather than
approximating: the measurement escapes spaces, commas and newlines but **not** `=`; tags and fields serialise in
sorted key order (the clients keep them in `TreeMap`s); a tag with an empty value is dropped entirely; `int`/`long`
fields carry the `i` suffix; booleans are bare `true`/`false`; string fields are double-quoted with `\` and `"`
escaped; the timestamp is nanoseconds. Target selection follows the plugin's version rules:

- v2 (organization set): `POST /api/v2/write?org=&bucket=&precision=ns`, `Authorization: Token ...`;
- v3 (token, no organization): `POST /api/v3/write_lp?db=&precision=nanosecond`, `Authorization: Bearer ...`;
- v1 (neither): `POST /write?db=&precision=ns[&rp=]`, optional Basic auth.

## Differences worth knowing

- **A rejected write fails the step by default.** The plugin's `exposeExceptions` defaults to false and it logs and
  swallows the exception, so builds stay green while nothing arrives. `failOnError` is true here; set it to false for
  the old behaviour.
- **There is no target list and no credentials store.** URL, token and basic auth arrive as inputs/secrets, which is
  what a step can actually carry.
- **The write API version is not discovered by pinging.** The plugin probes `/ping` and `/health`, tries v3 then v1; a
  step that knows its server is simpler and one request shorter. `apiVersion=auto` picks by inputs, and an explicit
  `1`/`2`/`3` overrides it.
- **`customTags`/`customFields` are `key=value` lines**, not `java.util.Properties`; the plugin's
  `jenkinsEnvParameterTag`/`Field` allow Properties escapes and `${...}` substitution - the substitution is kept, the
  escapes are not. A custom tag may not replace a tag this atom writes (the plugin would silently overwrite).
- **Not ported:** the optional generators that read other plugins' data from the build (JaCoCo, Cobertura, JUnit XML,
  Robot Framework, Performance, SonarQube, Git changelog, Metrics, Serenity, PerfPublisher) - they read Jenkins build
  actions and workspace files that an atom does not have; and the global listener / freestyle job configuration.

## Inputs

`url`, `apiVersion` (auto|1|2|3), `organization`, `bucket`, `database`, `retentionPolicy`, `token` (password),
`username`/`password`, `measurementName`, `projectPath` (default `${JOB_NAME}`), `projectName`, `customPrefix`,
`instance`, `buildNumber` (default `${BUILD_NUMBER}`), `buildResult` (default `${BUILD_STATUS}`), `buildStatusMessage`,
`buildDurationMs`, `buildScheduledTimeMs`, `buildExecTimeMs`, `agentName` (default `${NODE_NAME}`), `branchName`
(default `${BRANCH_NAME}`), `buildCauser`, `buildUser`, `buildCause`, `buildHealth`, `lastSuccessfulBuild`,
`lastStableBuild`, `testsTotal`/`testsFailed`/`testsSkipped`, `customTags`, `customFields`, `timestampMs`,
`useScheduledTimeAsTimestamp`, `dryRun`, `failOnError`, `timeoutSeconds`.

`${VAR}` in an input is taken from the job's parameters or the environment, like a Jenkinsfile would.

## Outputs

`written` (1, or 0 for dry run / swallowed failure), `apiVersion`, `measurement`, `endpoint`, `httpStatus`,
`timestamp` (nanoseconds) and `lineProtocol` - the exact line that was sent, which is also the whole output of a dry
run.

## Error codes

| code | meaning |
|---|---|
| 40001 | `url` missing or not http(s) |
| 40002 | `apiVersion` is not auto/1/2/3 |
| 40003 | a required input for the chosen API version is missing (db, org/bucket/token, password without user) |
| 40004 | `measurementName` blank, or `projectPath` empty |
| 40005 | a number input is not a number, or a time/duration is negative |
| 40006 | `buildResult` is not one of the five Jenkins results |
| 40007 | test counts are not integers, or failed+skipped exceeds the total |
| 40008 | a custom tag/field line is malformed, or replaces a tag this atom writes |
| 40009 | InfluxDB could not be reached |
| 40010 | InfluxDB answered non-2xx (the server's message is kept) |

## Verification

`mvn verify` runs `src/test/java/atom/InfluxdbIT.java`, which starts a fake InfluxDB that parses the line
protocol with its own parser (escapes, tag and field
ordering, types, timestamp) and checks the auth header and query string of the version in use. It then recomputes the
expected point from the atom's inputs - project name/namespace, ordinal, `build_successful`, which test fields must be
absent, custom field types - and compares. 24 cases cover the three API versions, auth, auto-detection, escaping
(space/comma/equals/quote/backslash), type and order rules, running builds, `${VAR}` expansion, local rejections that
must not reach the network, 401/400/500 and connection-refused paths, dry run and swallowed failures.

`tools/mutations.py` plants 14 bugs (missing `i`, wrong order, unescaped quotes/spaces, UNSTABLE ordinal,
`ordinal == 0`, kept empty tags, forced v1, reserved tag allowed, bad test counts, millisecond timestamp, Bearer on v2,
swallowed rejection, wrong v2 path) and every one must turn its case red.
