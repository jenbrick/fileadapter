# FileAdapter

A Spring Boot / Gradle worker that reads files from a mounted LAN directory, maps records into nested API request objects, and posts them to an existing upsert service. Preview mode is the default.

Java 17 · Spring Boot 3.5.16 · Gradle Wrapper 8.14.3 · Jackson · Apache Commons CSV · local H2 ledger.

## Run the working CSV example

Use Java 17 or later to launch Gradle. The build uses JDK 17 for compilation and tests; if that JDK is missing, Gradle downloads it automatically through the Foojay toolchain resolver. The first build needs internet access. This does not change your system Java or `JAVA_HOME`.

Then:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment.csv
```

On Windows, use `gradlew.bat test bootJar`. The Java command is the same.

In Git Bash on Windows, use `./gradlew test bootJar`.

If an older checkout reports `Cannot find a Java installation ... languageVersion=17` and `Toolchain download repositories have not been configured`, pull the fix and restart Gradle:

```bash
git pull --ff-only
./gradlew --stop
./gradlew test bootJar
```

If downloads are blocked by your network, install a JDK 17 manually, set `JAVA_HOME` to its installation directory (not its `bin` directory), and restart Git Bash. `java -version`, `javac -version`, and `./gradlew -q javaToolchains` help check the detected installations. The toolchain download does not provide the initial Java runtime needed to launch Gradle.

The example generates two nested employee requests. Inspect `var/preview/<run-id>/requests.jsonl` and compare them with `examples/expected-requests.jsonl`. Each line is a separate JSON request object. No HTTP requests are made in preview mode. A successful one-shot run exits with code 0; a failed or paused run exits with code 1. The source example is copied, not removed.

Repeated processing of identical content with the same profile, mode, and delivery settings returns the existing run. Changing from PREVIEW to LIVE creates a different run.

## Post to your service

Configure the endpoint and optional bearer token through environment variables:

```bash
export UPSERT_URL='http://localhost:8081/employees/upsert'
export UPSERT_BEARER_TOKEN='your-token'
java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.file=examples/enrollment.csv \
  --ingestion.mode=LIVE
```

PowerShell equivalents:

```powershell
$env:UPSERT_URL = 'http://localhost:8081/employees/upsert'
$env:UPSERT_BEARER_TOKEN = 'your-token'
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.file=examples/enrollment.csv --ingestion.mode=LIVE
```

The application sends one POST per data record, with `Content-Type: application/json`. Jackson serializes an `ObjectNode`; the application does not manually concatenate JSON. The default acknowledged statuses are 200, 201, and 204. A 202 response is uncertain until your service's asynchronous completion contract is implemented or you explicitly configure it as an acknowledgement.

Authentication currently supports an optional bearer token. Endpoint and request/response contracts are examples until your service details are supplied.

## Watch a LAN directory

Mount the share through the operating system and configure the path accessible to the worker's service account. For example, `/mnt/lan/enrollment/incoming` on Linux, or a UNC path on Windows. The application does not mount SMB shares or manage share credentials.

```bash
export FILEADAPTER_INPUT_DIR='/mnt/lan/enrollment/incoming'
export FILEADAPTER_WORK_DIR='/srv/fileadapter/var'
java -jar build/libs/fileadapter-0.1.0.jar
```

This polls in PREVIEW mode. Set `FILEADAPTER_MODE=LIVE` to enable delivery.

Producer protocol:

1. Finish writing `enrollment_20261027.csv` and close it.
2. Create an empty sibling file named `enrollment_20261027.csv.ready`.
3. Do not change the data file after publishing its marker.

The poller requires both the marker and a minimum file age. It ignores hidden files, symbolic links, and marker files. It copies the data to local durable storage and registers the run before removing the incoming marker and data file. Unknown or ambiguous profiles are quarantined. A header-only file is rejected as containing no data.

**Deployment scope: one worker process and one owner of the incoming directory.** Keep the work directory on a persistent local filesystem. A process lock prevents two workers using the same work directory. Multiple workers with different work directories sharing an input mount are not supported; distributed claiming would need a shared transactional store. The intake poller uses Spring scheduling; it can be replaced with Spring Integration without changing parsing, mapping, or delivery.

## Change input and output structures

The active example profile is in `src/main/resources/application.yml`. During development, edit it and restart. In deployment, use an external configuration file:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.config.additional-location=file:./config/feed.yml
```

Spring replaces configuration lists rather than merging their elements. Supply the **complete** `ingestion.profiles` list in an override. The simplest starting point is a copy of the supplied `application.yml`, edited for your feed. Keep credentials in environment variables, not in the profile.

Each profile contains:

| Section | Purpose |
| --- | --- |
| `id`, `filename-pattern` | Explicit feed identity and filename routing; exactly one profile must match |
| `layout` | Parser format, charset, delimiter, headers, and logical source-field aliases |
| `mappings` | Logical source paths to nested output paths, defaults, transformations, value translations |
| `contract` | Required output paths, JSON types, and nullability |

For one-shot runs, `--ingestion.profile-id=enrollment-csv-v1` explicitly selects a profile regardless of filename. Polling always uses filename routing.

Example mapping:

```yaml
- source: email
  target: person.contact.email
  blank-policy: OMIT
  transforms: [trim]
```

`layout.fields` resolves physical column names into logical source fields. All configured aliases present in a row must agree. Column names are case-sensitive. Unknown columns are ignored when logical fields are configured. If `layout.fields` is empty, mappings address raw column names directly.

Supported transformations: `trim`, `uppercase`, `lowercase`, `integer`, `decimal`, `boolean`, `iso-date`, and `us-date`. Boolean accepts only true/false, case-insensitively. `us-date` requires a valid `MM/dd/yyyy` date and produces ISO `yyyy-MM-dd`. Integer identifiers stay strings unless you explicitly apply `integer`, preserving leading zeroes.

Defaults are strings applied to **missing fields only**, then transformed. Transformations run in their listed order, followed by blank handling and then optional `values` translation. An unknown translation value is rejected. Nulls bypass transformations and remain null. Conversion of a blank numeric/date value fails; use blank handling on textual fields or a custom transform when you need a different rule.

Blank policies: `OMIT` (default), `NULL`, `EMPTY`, `REJECT`. Missing optional fields are omitted. Missing/null/blank required mapping fields fail. This preserves distinctions important to upserts: absent means whatever your API defines, explicit null may clear a field, and an empty string is a third value. Confirm these semantics with the service team.

The output contract checks required paths, types, and nullability; it is **not a full JSON Schema implementation**. String format checks are currently expressed through transformations. Adapt the validator to your service's maintained OpenAPI/JSON Schema when available.

## Other adapters and business extensions

| Format | State |
| --- | --- |
| CSV with headers | Active example, implemented |
| Headerless CSV, pipe, tab | Implemented through the configurable delimited adapter |
| Fixed-width, XML, JSON | Declared format identifiers and registry extension points; parsing not implemented |

Headerless layout example:

```yaml
format: DELIMITED
charset: UTF-8
delimiter: "|"
header-mode: NONE
columns: [employee_id, first_name, last_name, email, city, state, plan_code, effective_date]
```

UTF-8 BOMs, quoted commas, escaped quotes, and multiline CSV values are supported. Duplicate/blank headers and inconsistent record widths are rejected. Configure a non-UTF-8 charset explicitly; automatic encoding detection is not provided.

See [the extension guide](docs/extensions.md) for adding adapters, named Java transformations, richer validators, or aggregate mapping. No placeholder adapter silently accepts unimplemented formats.

## Processing, failure, and recovery

1. Copy input locally and compute SHA-256.
2. Derive run identity from content plus a complete profile/delivery/mode snapshot.
3. Store the immutable snapshot and source input.
4. Stream parsing and mapping into a durable request ledger. Validate the **whole file before any POST**.
5. Preview to JSON Lines or deliver the staged requests sequentially.
6. Persist acknowledgement per request and archive only when all requests are acknowledged.

Requests are staged in H2, not accumulated as a whole-file Java list. Disk space grows with source and request sizes. The ledger intentionally retains payloads for recovery and audit; define retention, filesystem access controls, and backups for your environment. Logs and error reports omit source values, bearer tokens, and HTTP bodies. Input archives, preview files, configuration snapshots, and ledger payloads can contain sensitive information.

```text
var/
  state/                 H2 database and process lock
  processing/<run-id>/    Input and snapshot while active or paused
  preview/<run-id>/       requests.jsonl (preview runs)
  archive/<run-id>/       Input and snapshot after success
  errors/<run-id>/        Error report; failed inputs under input-files/
```

Temporary copy files left by a crash before run registration have no network effects. They can be removed when no worker is running. Do not remove registered processing directories or edit the ledger while the worker is running.

Automatic startup/poll recovery handles staged, ready, delivering, and finalizing runs using their saved snapshot and payloads. A prior in-flight POST is replayed only when `replay-safe` is true. A crash after archive movement but before completion recording is reconciled. Mapping code extensions must remain compatible with snapshots being resumed; the snapshot's engine version is checked, but arbitrary Java implementation changes are not automatically versioned.

**Idempotency is a service guarantee, not a property created by sending a header.** The application always supplies a stable `<run-id>:<record-number>` key by default. Set `ingestion.delivery.replay-safe=true` only after confirming your service honors it and its retention covers your recovery window. The default is false. A changed file/configuration creates new keys, so this is not business-level duplicate detection across changed files.

When replay is safe, 408, 429, 5xx, and uncertain transport outcomes use bounded exponential backoff. Retries currently use the configured delay rather than `Retry-After`. Other non-acknowledged HTTP statuses are permanent rejections, except unconfigured 2xx statuses, which are uncertain. Redirects are not followed.

A paused replay-safe run can be explicitly resumed with another retry budget:

```bash
java -jar build/libs/fileadapter-0.1.0.jar --ingestion.resume=RUN_ID
```

Acknowledged records are skipped. Resume uses the original endpoint, mapping, mode, and retry settings; the current bearer token is used so credentials can rotate. Unsafe uncertain deliveries remain paused, including explicit resume: reconcile them with the service before any deliberate replay. This version does not provide an operator reconciliation UI/command. Permanently rejected or invalid runs are not resumed; correct the input/configuration and submit a new run. Previously applied business records require the service's agreed upsert semantics.

H2 is the durable ledger for this single-instance baseline. There are no cross-system transactions or exactly-once guarantees. API batches, grouping multiple rows into aggregates, asynchronous acknowledgement tracking, distributed processing, and a full schema validator are future extensions.

## Tests and architecture

```bash
./gradlew test
```

Tests cover parser edge cases, nested mapping, aliases, types, presence rules, preview output, marker-based intake, whole-file preflight, real HTTP POST serialization, retry identity, partial success, interrupted unsafe delivery, and timeout/202 classification. HTTP tests use a local in-process server and do not call your existing service.

Micrometer counters: `fileadapter.files` by final status and `fileadapter.requests` by delivery outcome. This is a non-web worker; actuator HTTP endpoints are not exposed. Add a metrics exporter for deployment monitoring.

See [architecture](docs/architecture.md) for the implemented flow and boundaries. No GitHub Actions or external deployment are configured.
