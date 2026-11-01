# FileAdapter POC

> V2: plain-English copy. [Original for comparison](README.md). Commands, configuration examples and diagrams are unchanged.
This Spring Boot/Gradle POC reads files and turns them into JSON requests. It keeps file reading, lookups, JSON building and HTTP delivery separate. Consent is the example; the mapping engine can support other uses.

Built with Java 17, Spring Boot 3.5.16, Gradle Wrapper 8.14.3, Jackson and Apache Commons CSV. H2 on disk keeps processing history.

## What we propose for January

Start with the [January overview](docs/january-delivery-plan-overview-v2.md), then the [detailed plan](docs/january-delivery-plan-detailed-v2.md) for scope, service decisions and acceptance checks. The [January class and sequence diagrams](docs/january-release-diagrams-v2.md) show the proposed design, successful flow and errors.

These describe the smaller proposed release. The POC demonstrates additional options. January starts with CSV; items still being discussed are marked.

## Look up the group before the member

The flow is:

**External group ID → group service → internal group number/customer ID → member demographics service → contract/PIN/member GUID → consent JSON.**

`GroupEnricher` runs before `ConsentMemberEnricher`. The member request contains only member demographics/identifiers and the resolved group values. It does not contain consent preferences, payer/coverage information or the consent upsert body.

The [lookup guide](docs/group-member-lookups-v2.md) gives the POC request formats, where to adapt the company services, fixtures and recovery behavior. LIVE needs HTTP at both stages: `GROUP_LOOKUP_MODE=HTTP` / `GROUP_LOOKUP_URL`, and `FLEXIBLE_LOOKUP_MODE=HTTP` / `CONSENT_LOOKUP_URL`. Tokens are separate. The packaged demos use fixtures in PREVIEW.

## Send one contract or several, with a limit

`flexible-contract-single-v1` builds one complete contract per POST. `flexible-contract-array-v1` builds an array of up to `CONTRACTS_PER_REQUEST` contracts, default two. Both keep members’ separate payer consents and their coverages. The receiving service still needs to agree to these JSON formats.

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
# Repeat with --ingestion.profile-id=flexible-contract-array-v1 for bounded arrays.
```

The eight-row CSV has three contracts and five members, with repeated contract rows scattered through the file. It produces three single-contract bodies or two array bodies. Default contract limits are 25 distinct members and 100 source rows. An oversized contract fails preparation rather than being split.

See [contract examples](docs/contract-batching-v2.md) for expected JSON, limits, payer consent identity and restart behavior. Older whole-file examples remain for comparison.

## Start with the basic CSV

From the repository root in Git Bash:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

The demo defaults to PREVIEW and synthetic group/member data. With those defaults it makes no external HTTP calls. Open `var-flexible-demo/preview/<run-id>/flexible-consents.csv.preview.json`.

Three rows describe two members and two payer coverages. They become one `{ "consents": [...] }` request. Each consent has `consentManager`; provider coverage is `[]`, and the payer consent contains its coverage array.

The body matches [expected-envelope.json](examples/flexible/expected-envelope.json) and the [submitted sample](examples/json-sample/CONSENT%20JSON%20ARRAY.txt), excluding that sample’s descriptive heading.

The [developer walkthrough](docs/developer-walkthrough-v2.md) follows the code. The [mapping guide](docs/flexible-mappings-v2.md) explains the settings.

## Change JSON without changing Java

Try the same CSV with an array as the whole body:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-root-array-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Or use `flexible-reshaped-v1` to move entries to `payload.entries`, move manager into transaction details and rename coverage to `payerInfo.coverages`. Expected files are included. These show possible changes; they do not mean the service accepts every format.

The [YAML](src/main/resources/application-flexible-demo.yml) controls source fields, conversions, constants/nulls, nested objects, row selection/grouping, required fields and request boundaries. It also includes a simple per-record name example without consent lookups.

## Write customer results and the IT report

Customer files contain original source records plus `RESULT_CODE` and `RESULT_REMARKS` in the incoming format. HARD stops the file during preparation. MEDIUM rejects a contract. SOFT warns and continues. An optional compatible service response can identify one failed member so only that member’s submitted original rows are returned.

Use approved customer wording. Keep detailed findings in the separate IT JSON report.

```bash
bash scripts/run-error-examples.sh
```

Default result folders are `var-flexible-demo/results/customer/<run-id>/` and `var-flexible-demo/results/internal/<run-id>/`. Set `CUSTOMER_RESULTS_DIR` / `INTERNAL_RESULTS_DIR` for mounted LAN folders. Another process sends the files/email.

Use `ready.json` to identify published results and distinguish PREVIEW from LIVE. The [error guide](docs/error-handling-v2.md) explains field sizes, partial results and matching service failures to original rows.

## Run every format and example

```bash
bash scripts/run-examples.sh
```

Examples cover CSV/pipe with and without headers, semicolon, tab, fixed width, XML, JSON array/object and JSON Lines. The script also runs alternate JSON, complete-contract and error examples. See [formats and expected files](docs/formats-v2.md).

With Python 3, compare generated outputs with expected files:

```bash
python scripts/verify-examples.py
```

For a new syntax, add `FileFormatAdapter`. For a new lookup, add `RecordEnricher`. For a new field conversion, add `ValueTransform`. A different delimiter or JSON structure usually needs settings only.

The [Git Bash guide](docs/git-bash-run-guide-v2.md) has Windows setup and copy/paste commands.

## Build setup

Java must be available through `JAVA_HOME` / `PATH` before Gradle can start. The project compiles/tests with JDK 17 and uses Foojay to obtain that toolchain if missing and permitted by the network. The first build needs approved download access.

Point `JAVA_HOME` at the JDK folder, not `bin`. Reopen Git Bash after changing Windows environment variables.

```bash
java -version
javac -version
./gradlew -q javaToolchains
```

Use `./gradlew` in Git Bash or `gradlew.bat` in Command Prompt. The JAR is `build/libs/fileadapter-0.1.0.jar` even if the company clone folder is `file-adapter-poc`.

## Sending, saved progress and supported limits

PREVIEW writes request bodies. LIVE sends them. PREVIEW can use HTTP lookups, but LIVE refuses fixtures. Set `UPSERT_URL` / `UPSERT_BEARER_TOKEN` separately from [lookup settings](docs/group-member-lookups-v2.md).

Prepare/check the supported-size file before sending. `GROUPED` keeps complete contracts together and limits contracts per request. `FILE` and `RECORD` are also available. H2 saves configuration, enriched rows, JSON, source references and delivery status. Resume uses saved bodies. Replay defaults to disabled until the service guarantees duplicate-safe requests.

The POC uses one worker and a local work folder. Polling waits for a closed input plus its `.ready` marker. A one-file command copies its input. For LAN polling, configure the mounted path, filename patterns and `--ingestion.poll-enabled=true`. The application does not mount SMB shares or manage credentials.

Examples allow 1,000 records; the supported configurable maximum is 10,000. Preparation uses memory. Limiting contracts per HTTP request does not remove the file limit or check JSON bytes. This is not unlimited-file streaming, distributed work assignment or a guarantee of exactly-once delivery. Putting changes together does not make them one transaction.

Member/contract results use an optional POC response agreement. Do not assume the actual endpoint supports it. Inputs, lookup results, previews and archives may hold member information; apply appropriate access/retention rules. Repository data is synthetic.

## Guides

| Guide | What it covers |
| --- | --- |
| [Runtime diagrams](docs/runtime-diagrams-v2.md) | Current classes, calls, errors and retries |
| [CSV debugger walkthrough](docs/csv-debug-walkthrough-v2.md) | Follow receipt through sending in the debugger |
| [Group/member lookup](docs/group-member-lookups-v2.md) | External group resolution and member-only request |
| [Customer results and IT diagnostics](docs/error-handling-v2.md) | Original-record returns and service failures |
| [Contract grouping](docs/contract-batching-v2.md) | Both JSON formats, members, payer consents and limits |
| [Developer change guide](docs/developer-walkthrough-v2.md) | Change layouts, fields and nesting |
| [Mapping settings](docs/flexible-mappings-v2.md) | Template syntax, grouping and enrichment |
| [Runnable formats](docs/formats-v2.md) | Inputs, profiles and expected results |
| [Architecture](docs/architecture-v2.md) / [diagram source](docs/architecture.mmd) | How the pieces fit |
| [Extension guide](docs/extensions-v2.md) | Where to add behavior |
| [Verification history](docs/verification-v2.md) | What was checked for earlier changes |

Old employee mappings, the consent-specific builder, superseded examples/scripts and PowerPoint were removed. Flexible projection is the current path. Earlier flexible snapshots remain readable, but engine versions 1–3 need the previous Git revision. Keep old work folders until delivery outcomes are settled. New configuration creates a new run; do not automatically resubmit old LIVE input.
