# Follow a CSV from receipt to the consent POST

> V2: plain-English copy. [Original for comparison](csv-debug-walkthrough.md). Commands, configuration examples and diagrams are unchanged.
Read this like a debugger session: stop at a method, look at its variables and step into the next call. It uses the actual [CSV example](../examples/flexible/flexible-consents.csv) and [demo YAML](../src/main/resources/application-flexible-demo.yml). “Projection” means the configured instructions for building outgoing JSON.

Keep the [runtime diagrams](runtime-diagrams-v2.md) open alongside it. The [developer guide](developer-walkthrough-v2.md) explains how to change layouts/mappings; this document follows execution.

## 1. Start a fresh debugging run

From the repository root in Git Bash:

```bash
./gradlew test bootJar
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 \
  -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.work-directory=./var-csv-debug-preview \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Attach your IDE’s Java remote debugger to localhost:5005 and continue. The JVM waits before starting the application. Use source from the checkout that built the JAR. Method breakpoints are easier to keep current than line numbers.

The first trace uses the original whole-file (`FILE`) example. Section 18 follows complete-contract requests.

The demo defaults to PREVIEW with synthetic lookups. It builds JSON without POSTing it. Sections 14–15 show LIVE with compatible HTTP services. Use a fresh work folder for a fresh trace: identical content/configuration can reuse a completed run and skip parsing.

## 2. Know the input

There is a header and three data records. Record numbers count logical CSV records; a quoted value can span several physical lines.

| Record | External contract | Resolved member / PIN | Provider preference | Payer preference | Payer / policy |
| --- | --- | --- | --- | --- | --- |
| 1 | EXTERNAL-001 | MEMBER-GUID-001 / 001 | GRANT | blank | blank |
| 2 | EXTERNAL-002 | MEMBER-GUID-002 / 002 | GRANT | GRANT | PAYER-100 / POL-456789 |
| 3 | EXTERNAL-002 | MEMBER-GUID-002 / 002 | GRANT | GRANT | PAYER-200 / ABC-998877 |

Both members resolve to internal contract `123456789`. Rows 2 and 3 describe the same member with different coverage. They should become one payer consent with two coverages.

## 3. Start Spring and inspect settings

**Stop at:** [FileAdapterApplication.main()](../src/main/java/com/jenbrick/fileadapter/FileAdapterApplication.java), after `SpringApplication.run(...)`.

Inspect `properties.file()`, `profileId()`, `mode()` and `workDirectory()`.

Spring loads `application.yml` and `application-flexible-demo.yml`. `spring.profiles.active` chooses Spring configuration. `ingestion.profile-id` optionally chooses a feed definition inside it. This example supplies a file without an explicit feed ID.

During startup:

- [ProfileCatalog](../src/main/java/com/jenbrick/fileadapter/config/ProfileCatalog.java) checks IDs, filename patterns, layouts, templates and enricher settings.
- [AdapterRegistry](../src/main/java/com/jenbrick/fileadapter/source/AdapterRegistry.java) registers file readers by format.
- [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) registers enrichers by name.
- [RuntimeConfiguration](../src/main/java/com/jenbrick/fileadapter/config/RuntimeConfiguration.java) locks the work folder and opens file-backed H2 at `<work>/state/ledger`.

Invalid configuration can stop startup before any file is processed.

## 4. Receive a file from the command line or poller

**Command-line stop:** `FileAdapterApplication.main()`, at:

```java
processor.process(properties.file(), properties.profileId(), false);
```

`consume=false` leaves the source file in place. After processing, `successful(runId)` determines the exit code. Completed preview/live runs, including completed runs with errors, exit 0; HARD file failure exits 1. Inspect status/results to distinguish partial success.

**Polling stop:** [DirectoryPoller.poll()](../src/main/java/com/jenbrick/fileadapter/processing/DirectoryPoller.java).

The producer closes the CSV, then creates `flexible-consents.csv.ready`. Enable polling because the demo normally disables it:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.poll-enabled=true \
  --ingestion.input-directory=./var/incoming
```

The poller first calls `recover()`. It selects regular, non-hidden files with a ready marker and sufficient age (default two seconds), then calls `process(input, null, true)`. A file/resume command disables polling. The LAN share must already be mounted and accessible.

## 5. Choose the feed definition

**Stop at:** [FileProcessor.process()](../src/main/java/com/jenbrick/fileadapter/processing/FileProcessor.java), then `profiles.resolve(input, profileId)`.

`flexible-consents.csv` matches `flexible-consents{,-header*}.csv`, selecting `flexible-csv-v1`. Exactly one match is required. An explicit profile ID bypasses filename matching.

Inspect:

```text
profile.id()                        flexible-csv-v1
profile.layout().format()           DELIMITED
profile.layout().delimiter()        ,
profile.layout().headerMode()       FIRST_RECORD
profile.projection().scope()        FILE
profile.projection().maxRecords()   1000
profile.projection().enrichers()    [consent-member]
```

These settings separately control reading, lookups and outgoing JSON.

## 6. Copy the input and create the run

**Stop at:** `FileProcessor.process()`, around `new RunSnapshot(...)` and `ledger.create(...)`.

The version-4 snapshot saves profile, mode and delivery settings. The processor copies the input, calculates its SHA-256 and combines that with the serialized snapshot to create the run ID. Filename is not part of this identity.

A new run writes:

```text
<work>/processing/<run-id>/input
<work>/processing/<run-id>/snapshot.json
```

It creates an `ingestion_run` row with `STAGING` status. An existing ID reuses the saved run. Polling also verifies the producer did not change the input, then removes the incoming file/marker after saving it locally. The producer must leave a marked file alone.

Later YAML edits do not change that saved run’s settings.

## 7. Start preparation and select the reader

**Stop at:** `FileProcessor.execute(id, false)`, inside `STAGING`.

Inspect status and snapshot. The processor checks the saved input checksum and clears incomplete staged requests. It does not send anything in STAGING.

Step into:

```java
adapters.require(snapshot.profile().layout().format()).read(...);
```

`AdapterRegistry.require(DELIMITED)` returns [DelimitedFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/DelimitedFileAdapter.java). CSV, pipe, semicolon and tab use this same class with different settings.

## 8. Parse a CSV record

**Stop at:** `DelimitedFileAdapter.read()`, at `consumer.accept(new SourceRecord(...))`.

Apache Commons CSV uses RFC4180 parsing with the configured delimiter. Input is UTF-8; malformed encoding is rejected. The reader handles a BOM, reads the header and rejects duplicate/blank names or mismatched column counts.

Inspect `row.getRecordNumber()` and `data`. Record 2 includes:

```json
{
  "EXTERNAL_CONTRACT_ID": "EXTERNAL-002",
  "FIRST_NAME": "John",
  "LAST_NAME": "Smith",
  "PROVIDER_CLINICAL_CONSENT_PREFERENCE": "GRANT",
  "PAYER_CONSENT_PREFERENCE": "GRANT",
  "PAYER_ID": "PAYER-100",
  "PAYER_POLICY_ID": "POL-456789"
}
```

CSV values start as strings, including blanks and numeric-looking IDs. This Jackson object represents input data, not the consent request.

Stepping into the consumer returns to the callback in `FileProcessor.execute()`. `ResultStore.capture()` saves the original row, and the processor collects the supported-size input. Once parsing finishes, `PreparationCoordinator.prepare()` handles normalization and lookups.

## 9. Normalize field names

**Stop at:** [SourceNormalizer.normalize()](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java).

The stock CSV has no `layout.fields`, so it returns the record unchanged. With selectors/aliases, it creates an object using the agreed logical names. Only listed fields remain, conflicting aliases fail, and record number stays the same.

In the intake callback, inspect `currentRecord[0]` and `originalRows`. It checks increasing record numbers and the file-record limit while preserving originals. In `PreparationCoordinator`, inspect normalized/enriched rows and rejected contract keys.

## 10. Check the row and call the lookups

**Stop at:** `ProjectionEngine.enrich()`, `GroupEnricher.enrichForRun()`, then [ConsentMemberEnricher.enrich()](../src/main/java/com/jenbrick/fileadapter/projection/ConsentMemberEnricher.java).

The engine checks the input rules, then looks in [EnrichedRecordStore](../src/main/java/com/jenbrick/fileadapter/projection/EnrichedRecordStore.java) by run ID/record number. A saved row is reused; otherwise enrichers run in configured order.

First inspect `EXTERNAL_GROUP_ID`, `EXT-GROUP-1` here. `GroupEnricher` checks `GroupContextStore`. If no saved result exists, it calls `GroupResolver` with only that ID. The saved context is internalGroupNumber `10001`, customerId `CUST-1`. Missing/incomplete context stops the member call.

Next, `ConsentMemberEnricher` builds the nine-property `MemberLookupPayload` shown in the [lookup guide](group-member-lookups-v2.md). It trims strings, checks required identifiers and parses birth date. It includes member information plus the group result. It excludes consent preferences, payer IDs/policies, consent keys, target/resolve flags and any whole-record wrapper. A row with neither preference skips both lookups.

Step into [ConfiguredMemberResolver.resolve()](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredMemberResolver.java): FIXTURE needs exactly one match; HTTP POSTs only that typed payload and expects HTTP 200 with a resolution body. Adapt this POC format to the company API here.

On return, check owner/manager identities and three-character PIN. Record 2’s added `resolved` information includes:

```json
{
  "owner": {
    "contract": "123456789",
    "pin": "002",
    "contractMemberGuid": "MEMBER-GUID-002"
  },
  "manager": {
    "contract": "123456789",
    "pin": "002",
    "contractMemberGuid": "MEMBER-GUID-002"
  },
  "providerConsentGuid": null,
  "payerConsentGuid": null
}
```

It also has `sourceDateTime`. The supplied template uses CSV `SOURCE_DATETIME` for outgoing audit time rather than that generated value. Null consent GUIDs remain explicit lookup values.

The engine saves the enriched row. All three sample rows share one saved group result. Member results are saved per source row, so records 2 and 3 can each call demographics on the first pass. JSON grouping combines their resolved identities afterward.

## 11. Build the outgoing JSON

**Stop at:** `PreparationCoordinator.prepare()` at `engine.project(...)`, then `ProjectionEngine.project()` / `render()`.

The basic sample has three enriched rows. FILE builds one request from them. RECORD would build each separately and could not combine the repeated member’s coverage. GROUPED gathers complete contracts first; see section 18.

`template()` converts Spring configuration into nodes, including its indexed-map representation of lists. `render()` walks those nodes:

| Node | What to watch |
| --- | --- |
| `object` | Create an ObjectNode and render children; omit MissingNode values |
| `source` | Use MappingValues on grouped rows; require resulting values to agree |
| `literal-json` | Parse the constant, including null/empty arrays/text |
| `array` | Select rows, group them in order and build an item per group |

[MappingValues.resolve()](../src/main/java/com/jenbrick/fileadapter/mapping/MappingValues.java) reads values and applies defaults, conversions, blanks, required checks and code translations. [TransformRegistry](../src/main/java/com/jenbrick/fileadapter/mapping/TransformRegistry.java) supplies trim, uppercase and date conversion. Node checks run after construction.

The root has `consents`, built from two collections:

1. Provider rows 1, 2 and 3 group by member GUID. They produce two entries because the repeated provider values for rows 2/3 agree.
2. Payer rows 2 and 3 group by member GUID. They produce one entry with two coverages, grouped by payer ID/policy ID.

Provider coverage is `[]`. Manager sits beside transaction details because YAML places it there. Moving it is a mapping change.

Compare `request.body()` with [expected-envelope.json](../examples/flexible/expected-envelope.json). Its structure is:

```text
consents[0] = PCDS, member 001, coverage []
consents[1] = PCDS, member 002, coverage []
consents[2] = P2PDS, member 002, coverage [PAYER-100, PAYER-200]
```

That is one request with three consents and two coverages. `request.sourceRecords()` is `[1, 2, 3]`. The root must be an object/array. If `skip-when-empty: consents` finds no consents, generate no request.

## 12. Save complete requests before sending

**Stop at:** [ProcessingLedger.stage()](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java).

The processor serializes the body and saves an `ingestion_request` row:

```text
record_number = 1              (request number, not CSV row number)
status        = PENDING
attempts      = 0
source_record = 1              (first contributing source record)
source_records = [1, 2, 3]
payload       = serialized complete JSON envelope
```

Only successful preparation makes the run READY. With default HARD handling, a parsing, lookup, grouping or validation error records PREFLIGHT, fails the run and moves processing files below `<work>/errors/<run-id>/input-files`. No upsert has been sent. Configured MEDIUM handling can instead reject a contract before final requests are assembled; section 19 shows that.

Group-conflict messages identify JSON paths and contributing rows. The general preflight record counter may show the last row read rather than the exact conflicting row.

Saved enriched rows avoid repeating successful preparation lookups. Saved request bodies preserve exactly what we intended to send during delivery recovery.

## 13. Follow PREVIEW

**Stop at:** [PreviewWriter.write()](../src/main/java/com/jenbrick/fileadapter/processing/PreviewWriter.java).

```text
var-csv-debug-preview/preview/<run-id>/flexible-consents.csv.preview.json
var-csv-debug-preview/preview/<run-id>/requests.jsonl
```

The named file includes source information. Each `requests.jsonl` line is a complete body; here there is one. JSON property order does not matter.

The processor sets FINALIZING, archives input/snapshot under `<work>/archive/<run-id>/` and finishes as PREVIEWED. No consent POST occurs. Revisiting the run can recreate missing previews from saved bodies.

## 14. Configure HTTP services for LIVE

Both group and member enrichment refuse fixtures in LIVE. Configure actual compatible lookup and upsert URLs first. See [mapping settings](flexible-mappings-v2.md) and [lookup formats](group-member-lookups-v2.md). Supply tokens through environment variables when needed.

After setting `GROUP_LOOKUP_URL`, `CONSENT_LOOKUP_URL` and `UPSERT_URL`:

```bash
export GROUP_LOOKUP_MODE=HTTP
export FLEXIBLE_LOOKUP_MODE=HTTP
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 \
  -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=LIVE \
  --ingestion.work-directory=./var-csv-debug-live \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Use an agreed test endpoint. This sends consent JSON. LIVE settings create a different saved run from PREVIEW.

## 15. Follow the POST

**Stop at:** `FileProcessor.deliver()`, then [HttpUpsertClient.post()](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java).

The run becomes DELIVERING. Saved requests are read in number order; acknowledged requests are skipped. For each unresolved request:

1. Check saved replay rules and attempt budget.
2. Save SENDING and increment attempts before the network call.
3. Parse the saved JSON body.
4. Call `client.post(body, id + ":" + request.number(), settings)`.

Inspect payload, source records, `<run-id>:1` and saved settings. The client sets JSON content type/accept, optional bearer authorization and the configured idempotency header. Spring RestClient POSTs the body; redirects are disabled.

The client does not parse CSV, look up members or rebuild the template.

## 16. Check acknowledgement, retry or failure

**Stop at:** the `HttpUpsertClient.post()` response callback, then `FileProcessor.deliver()` after `ledger.result(...)`.

| Response | Classification | Next action |
| --- | --- | --- |
| Configured success status, default 200/201/204 | ACKNOWLEDGED | Save and advance |
| Other 2xx | UNCERTAIN | Apply saved replay rules |
| 408, 429 or >=500 | RETRYABLE | Apply saved replay rules |
| ResourceAccessException, such as lost connection | UNCERTAIN | Apply saved replay rules |
| Other HTTP status | REJECTED | Fail with HTTP_REJECTED report |

This table describes default status-based handling. It does not make per-entry decisions from a response body. Optional `SCOPED_V1` checks structured member/contract outcomes instead; see section 19. Raw bodies are not persisted.

With default `replay-safe=false`, an unacknowledged attempt pauses instead of resending. Under an agreed duplicate-safe service contract, enabled retries keep the same body/key, with exponential delay capped at 30 seconds. Exhausted attempts or an interrupted retry delay pause the run.

After every request is acknowledged, `finish()` archives the run and marks COMPLETED. An archive failure leaves recoverable FINALIZING work without needing another POST.

## 17. Follow restart or explicit resume

**Stop at:** `FileProcessor.recover()`, `resume()` and the early `execute()` status branches.

The poller recovers STAGING, READY, DELIVERING and FINALIZING. PAUSED needs explicit resume; FAILED is not retried.

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.work-directory=./var-csv-debug-live \
  --ingestion.resume=YOUR_RUN_ID
```

Use the same work folder and required credentials. Resume uses saved configuration, not new mapping/delivery overrides. STAGING rereads staged input and reuses saved enriched rows. READY/DELIVERING uses saved bodies. FINALIZING finishes archiving.

Manual resume grants another attempt budget but does not override `replay-safe=false`. A prior delivery attempt under that policy pauses again for investigation. FAILED returns false. Runs from removed processor versions need their earlier release.

## 18. Follow one-contract and multi-contract requests

Use the debugger startup with `--ingestion.profile-id=flexible-contract-single-v1`, `--ingestion.file=examples/flexible/contract-batches.csv` and a fresh work folder. Repeat with `flexible-contract-array-v1`. Both default to PREVIEW; LIVE still needs HTTP lookups.

Eight rows describe three contracts and five members. Alex and John share EXT-CONTRACT-A; other identity fields distinguish them. See [the fixture guide](contract-batching-v2.md).

At `ProjectionEngine.project()`, inspect:

```text
projection.scope()                          GROUPED
projection.batching().groupBy()              [resolved.owner.contract]
projection.batching().maxGroupsPerRequest()   1 (single) or 2 (array default)
projection.batching().maxRecordsPerGroup()    100
projection.batching().maxMembersPerGroup()    25
```

After no-change filtering, step into `RequestBatcher.partition()`. It gathers internal contract 123456789 from rows 1/2/4/7, 222222222 from 3/5/8 and 333333333 from 6. Member limits count distinct GUIDs; row limits include selected coverage rows. Oversized contracts fail before POST rather than being split. The configured severity decides file failure versus contract rejection.

Single mode makes three requests. Array mode puts the first two contracts together and the last in a second request. In `projectOne()` / `render()`, the array’s root collection groups by contract, then builds each `contractId` and its member-owned `consents`.

John’s `P2P-A` combines rows 2/4 into GRANT with two coverages. `P2P-B` makes row 7 a separate RVOKE consent with one coverage. Provider information still makes one PCDS consent. The payer key stays in mapping, not member lookup or JSON. Existing consent GUIDs need a separate consent-identity integration.

At `ProcessingLedger.stage()`, inspect:

| Mode | Request 1 rows | Request 2 | Request 3 |
| --- | --- | --- | --- |
| Single | `[1,2,4,7]` | `[3,8,5]` | `[6]` |
| Array, limit 2 | `[1,2,4,7,3,8,5]` | `[6]` | None |

Bodies are prepared before delivery. If 1 succeeds and 2 pauses, resume skips 1 and uses 2’s saved body/key. Edited settings do not regroup that run. The example file cap is still 1,000; smaller HTTP requests do not give unlimited-file streaming.

## Breakpoint checklist

| Method | What to inspect |
| --- | --- |
| `FileAdapterApplication.main` | File/resume arguments and settings |
| `ProfileCatalog.resolve` | Chosen profile and filename rule |
| `FileProcessor.process` | Hash, snapshot, run ID and consume flag |
| `DelimitedFileAdapter.read` | Headers, record number and string values |
| `SourceNormalizer.normalize` | Input versus logical names |
| `ProjectionEngine.enrich` | Input checks and saved enriched row |
| `GroupEnricher.enrichForRun` / `GroupContextStore` | External group and saved internal group/customer values |
| `ConsentMemberEnricher.enrich` | Member payload, owner/manager and optional consent GUIDs |
| `ConfiguredMemberResolver.resolve` | Fixture or HTTP match |
| `ProjectionEngine.project` / `RequestBatcher.partition` / `render` | Scope, grouping, limits and JSON |
| `ProcessingLedger.stage` | Saved body and source references |
| `PreviewWriter.write` | PREVIEW files |
| `FileProcessor.deliver` | Body, attempts and retry rules |
| `HttpUpsertClient.post` | URL, headers and outcome |
| `FileProcessor.finish` | Archive and final status |

## 19. Follow customer results and IT diagnostics

Run `error-demo-medium-v1` with `contract-batches.csv`. At `ResultStore.capture()`, confirm eight original rows are saved before normalization. In `PreparationCoordinator.prepare()`, John’s MEDIUM rule rejects rows 1/2/4/7 for contract A. Morgan’s SOFT warning keeps those rows eligible. The two remaining contracts produce one array request.

At `ResultPublisher.publish()`, follow selection of failed originals and one approved code/remarks pair per row into `SourceResultWriter`. The CSV printer writes original fields first, then `RESULT_CODE` / `RESULT_REMARKS`. It does not copy internal member GUIDs, lookup namespaces or consent JSON into the customer file. The IT JSON includes every finding, including Morgan’s warnings, with source/request references.

For LIVE `SCOPED_V1`, stop at `ScopedResponseReader.read()`, `ProcessingLedger.result()` and `ServiceResultProcessor.apply()`. A member GUID selects that member’s submitted originals. Save results before generating files; recovery restores findings even for acknowledged requests, without duplicates. Unknown identities, missing completion guarantees or unmapped codes pause the run rather than guessing failed rows. See [the exact response rules](error-handling-v2.md).
