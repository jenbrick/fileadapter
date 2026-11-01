# CSV debugging walkthrough: receipt to consent POST

This follows the flexible POC using the real [CSV fixture](../examples/flexible/flexible-consents.csv) and [profile YAML](../src/main/resources/application-flexible-demo.yml). Read it as a debugger session: stop at each method, inspect the variables, then step into the next call. “Projection” is the configurable output mapping, not a Spring feature.

See the [sequence and class diagrams](runtime-diagrams.md) alongside this trace. The [existing developer guide](developer-walkthrough.md) covers changing layouts and JSON structures; this document focuses on execution.

## 1. Set up a reproducible run

From the repository root in Git Bash:

```bash
./gradlew test bootJar
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 \
  -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.work-directory=./var-csv-debug-preview \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Attach your IDE’s Java remote debugger to localhost port 5005, then continue. The JVM pauses before application startup. Attach source from the same checkout used to build the JAR. Use method breakpoints or break on the statements named below; line numbers change as the code evolves.

This first trace follows the original FILE-scope sample. For bounded contract requests and the interleaved CSV, follow section 18 as well.

The profile defaults to PREVIEW with synthetic lookup fixtures. It will generate the consent request but will not POST it. Sections 14–15 explain how to trace LIVE delivery after the real HTTP lookup facade is available. Choose a fresh work directory when you need a fresh trace: identical content plus identical saved configuration reuses a run and may bypass parsing entirely.

## 2. Understand what enters the pipeline

The CSV has a header plus **three data records**, numbered 1–3 by the CSV parser. Record numbers are data record numbers, not necessarily physical line numbers: quoted CSV fields can span lines.

| Record | External contract | Member after lookup | Provider preference | Payer preference | Payer / policy |
| --- | --- | --- | --- | --- | --- |
| 1 | EXTERNAL-001 | MEMBER-GUID-001 / PIN 001 | GRANT | blank | blank |
| 2 | EXTERNAL-002 | MEMBER-GUID-002 / PIN 002 | GRANT | GRANT | PAYER-100 / POL-456789 |
| 3 | EXTERNAL-002 | MEMBER-GUID-002 / PIN 002 | GRANT | GRANT | PAYER-200 / ABC-998877 |

Both resolved members belong to internal contract `123456789`. The last two records repeat the same member because each physical row supplies one payer coverage. They must become one payer consent with two coverages.

## 3. Start Spring and load the configuration

**Breakpoint:** [FileAdapterApplication.main()](../src/main/java/com/jenbrick/fileadapter/FileAdapterApplication.java), immediately after `SpringApplication.run(...)`.

Inspect `properties.file()`, `properties.profileId()`, `properties.mode()` and `properties.workDirectory()`.

Spring loads `application.yml` and `application-flexible-demo.yml`. `--spring.profiles.active` selects the Spring configuration; `--ingestion.profile-id` optionally selects a processing definition within it. The example command supplies a file but no processing profile ID.

During bean construction:

- [ProfileCatalog](../src/main/java/com/jenbrick/fileadapter/config/ProfileCatalog.java) validates every profile’s ID, filename glob, layout, projection and enricher settings.
- [AdapterRegistry](../src/main/java/com/jenbrick/fileadapter/source/AdapterRegistry.java) registers the Spring `FileFormatAdapter` beans by their format.
- [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) registers named `RecordEnricher` beans.
- [RuntimeConfiguration](../src/main/java/com/jenbrick/fileadapter/config/RuntimeConfiguration.java) locks the work directory and opens file-backed H2 at `<work>/state/ledger`.

Bad configuration may fail startup before `process()` is ever called.

## 4. Receive the file through CLI or polling

**CLI breakpoint:** `FileAdapterApplication.main()`, at:

```java
processor.process(properties.file(), properties.profileId(), false);
```

`consume=false` means the source file remains in place. After processing, the CLI calls `successful(runId)` and exits 0 for PREVIEWED or COMPLETED, otherwise 1.

**Polling alternative:** [DirectoryPoller.poll()](../src/main/java/com/jenbrick/fileadapter/processing/DirectoryPoller.java).

For a LAN-mounted input directory, the producer finishes writing and closes the CSV, then creates a sibling marker such as `flexible-consents.csv.ready`. Enable polling explicitly because the demo disables it:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.poll-enabled=true \
  --ingestion.input-directory=./var/incoming
```

The poller first calls `recover()`, then selects regular non-hidden input files that have the ready marker and meet the minimum age (default two seconds). It calls `process(input, null, true)`. CLI file/resume arguments disable polling. The application expects the share to be mounted already.

## 5. Select the file profile

**Breakpoint:** [FileProcessor.process()](../src/main/java/com/jenbrick/fileadapter/processing/FileProcessor.java), then step into `profiles.resolve(input, profileId)`.

`ProfileCatalog.resolve()` matches `flexible-consents.csv` against `flexible-consents{,-header*}.csv` and selects `flexible-csv-v1`. Exactly one match is required; an explicit profile ID replaces filename matching.

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

These settings separate physical parsing, optional domain lookup and output shape.

## 6. Copy the input and identify the run

**Breakpoint:** `FileProcessor.process()`, at `new RunSnapshot(...)`, then `ledger.create(...)`.

The processor builds an engine-version-4 snapshot containing the selected profile, mode and delivery settings. It copies the input to a temporary file, computes SHA-256 of its bytes, and derives the run ID from the content hash plus the serialized snapshot. The filename is not part of this identity.

For a new run it writes:

```text
<work>/processing/<run-id>/input
<work>/processing/<run-id>/snapshot.json
```

It creates an `ingestion_run` row with status `STAGING`. For an existing ID it reuses the recorded run. In polling mode it also verifies the producer’s input has not changed, then removes the marker and incoming file after local staging. A producer must not modify a file after publishing its marker.

This snapshot preserves the selected configuration for recovery; later edits to YAML do not rewrite an existing run.

## 7. Enter preparation and choose the adapter

**Breakpoint:** `FileProcessor.execute(id, false)` inside the `STAGING` branch.

Inspect `run.status()` and `snapshot`. The processor verifies the saved input checksum and clears incomplete staged requests. No delivery happens during STAGING.

Step into:

```java
adapters.require(snapshot.profile().layout().format()).read(...);
```

`AdapterRegistry.require(DELIMITED)` returns [DelimitedFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/DelimitedFileAdapter.java). CSV, pipe, semicolon and tab layouts all use this same class; delimiter configuration changes its behavior.

## 8. Parse one CSV record

**Breakpoint:** `DelimitedFileAdapter.read()`, at `consumer.accept(new SourceRecord(...))`.

The adapter creates an Apache Commons CSV RFC4180 parser with the configured delimiter. It reads UTF-8 with malformed input rejected, handles a BOM, reads the first record as headers and rejects duplicate/blank headers or inconsistent column counts.

Inspect `row.getRecordNumber()` and `data`. For record 2, this is an excerpt of the intermediate Jackson object:

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

All CSV cells start as strings, including empty strings and numeric-looking IDs. This object is source data, not the final consent JSON. The callback executes immediately for each row, so stepping into `consumer.accept()` returns you to the lambda inside `FileProcessor.execute()`.

## 9. Normalize physical field names

**Breakpoint:** [SourceNormalizer.normalize()](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java).

The stock CSV profile has no `layout.fields`, so this method returns the `SourceRecord` unchanged. For a feed with renamed headers, configured selectors and aliases create a new object with canonical logical field names. When fields are configured, only listed logical fields are retained; conflicting aliases are rejected. Record number is preserved.

Back in the callback, inspect `currentRecord[0]`. The processor enforces increasing record numbers and the configured record limit before adding the enriched row to `projectionRows`.

## 10. Validate and enrich the row

**Breakpoint:** `ProjectionEngine.enrich()`, then [ConsentMemberEnricher.enrich()](../src/main/java/com/jenbrick/fileadapter/projection/ConsentMemberEnricher.java).

The engine validates the input contract, checks [EnrichedRecordStore](../src/main/java/com/jenbrick/fileadapter/projection/EnrichedRecordStore.java) by run ID and source record number, and reuses the saved payload if present. Otherwise it invokes each configured enricher by name.

`ConsentMemberEnricher` detects which preferences are populated. A row with neither preference returns without a member lookup. For a populated row it builds lookup criteria from the canonical fields, trims values, validates required identity fields and parses date of birth. It includes the configured targets and `resolveProvider` / `resolvePayer` flags.

Step into [ConfiguredMemberResolver.resolve()](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredMemberResolver.java):

- FIXTURE mode requires exactly one fixture match; this is the default demo.
- HTTP mode POSTs criteria to the configured facade and requires HTTP 200 with a resolution body. This facade contract is the integration seam for the company lookup APIs.

On return, the enricher validates owner and manager identities, including a three-character PIN, and adds `resolved` to a copy of the normalized record. For record 2, inspect:

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

This is an excerpt of `enriched.payload().get("resolved")`; it also contains `sourceDateTime`. The working template takes its outgoing timestamp from CSV `SOURCE_DATETIME`, not that generated fallback. Null consent GUIDs remain explicit canonical lookup values.

The engine persists the enriched payload before returning it. Lookups are saved per source row, not cached globally by member: records 2 and 3 can each perform a lookup on the first pass. The mapping later groups their resolved identities.

## 11. Finish reading and construct JSON

**Breakpoint:** `FileProcessor.execute()` at `projections.project(...)`, then `ProjectionEngine.project()` and private `render()`.

After all three callbacks complete, `projectionRows.size()` is 3. FILE scope renders one request using the complete bounded collection. RECORD scope would render each row separately and could not combine repeated rows into one coverage collection. GROUPED scope partitions complete contracts first, then renders each bounded partition (section 18).

`template()` converts the configured root into the node model, normalizing Spring’s indexed-map representation of DSL lists. `render()` recursively handles:

| Node | Debugger behavior |
| --- | --- |
| `object` | Create an ObjectNode; render each named child; omit MissingNode children. |
| `source` | Resolve the source in each grouped row through MappingValues; require all resulting values to agree. |
| `literal-json` | Parse a configured constant, including null, an empty array or a string. |
| `array` | Filter rows, build ordered groups, render one item per group and append it. |

[MappingValues.resolve()](../src/main/java/com/jenbrick/fileadapter/mapping/MappingValues.java) performs source lookup, missing-value defaults, transforms, blank policy, required checks and code translation. [TransformRegistry](../src/main/java/com/jenbrick/fileadapter/mapping/TransformRegistry.java) supplies operations such as trim, uppercase and ISO date conversion. Configured node checks run after a node is rendered.

The working root is an object containing `consents`. It appends two configured collections:

1. Provider: select records 1, 2 and 3; group by `resolved.owner.contractMemberGuid`. This yields two provider entries. Rows 2 and 3 coalesce because mapped provider values agree.
2. Payer: select records 2 and 3; group by that same member GUID. This yields one payer entry. Its nested coverage array groups by `PAYER_ID` plus `PAYER_POLICY_ID`, yielding two coverage objects.

Provider coverage is the literal `[]`. Payer coverage comes from the grouped rows. Consent manager is a sibling of transaction details because the YAML places it there. Changing those paths is a mapping change, not a parser change.

Inspect the full `request.body()` against [expected-envelope.json](../examples/flexible/expected-envelope.json). A short structural view is:

```text
consents[0] = PCDS, member 001, coverage []
consents[1] = PCDS, member 002, coverage []
consents[2] = P2PDS, member 002, coverage [PAYER-100, PAYER-200]
```

That is **one request, three consent entries and two coverage entries**. `request.sourceRecords()` is `[1, 2, 3]`. The final JSON root must be an object or array. If `skip-when-empty: consents` finds an empty collection, no request is generated.

## 12. Stage the final request before delivery

**Breakpoint:** [ProcessingLedger.stage()](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java).

The processor serializes `request.body()` and inserts an `ingestion_request` row with:

```text
record_number = 1              (request number, not CSV row number)
status        = PENDING
attempts      = 0
source_record = 1              (first contributing source record)
source_records = [1, 2, 3]
payload       = serialized complete JSON envelope
```

The run becomes `READY` only after preparation succeeds for the whole file. A parse, lookup, grouping or validation failure is reported as PREFLIGHT, the run becomes FAILED and the processing files move beneath `<work>/errors/<run-id>/input-files`. No upsert has been sent. Group-conflict messages identify the JSON path and contributing row numbers; the general preflight record counter may indicate the last row read rather than the precise conflicting row.

The two saved data sets serve different purposes: enriched rows avoid repeated successful lookup work during interrupted preparation, while staged JSON preserves the complete intended request during delivery recovery.

## 13. Follow the PREVIEW branch

**Breakpoint:** [PreviewWriter.write()](../src/main/java/com/jenbrick/fileadapter/processing/PreviewWriter.java), called by `FileProcessor.execute()`.

PREVIEW writes:

```text
var-csv-debug-preview/preview/<run-id>/flexible-consents.csv.preview.json
var-csv-debug-preview/preview/<run-id>/requests.jsonl
```

The named preview includes source information. Each line in `requests.jsonl` is a request body; this sample produces one line containing the full envelope. JSON property order is not part of the contract.

The processor sets FINALIZING, archives `input` and `snapshot.json` under `<work>/archive/<run-id>/`, then marks the run PREVIEWED. No delivery call occurs. Reprocessing the same run can regenerate missing previews from saved bodies.

## 14. Prepare HTTP lookup and LIVE settings

The default fixture demo cannot simply be changed to LIVE: `ConsentMemberEnricher` explicitly refuses fixture-based member resolution in LIVE.

When a compatible lookup facade and upsert endpoint are available, use their actual URLs. The lookup request/response contract is described in [the mapping guide](flexible-mappings.md). Set optional bearer-token environment variables there if required; do not commit credentials.

For example, after supplying `CONSENT_LOOKUP_URL` and `UPSERT_URL` in your shell:

```bash
export FLEXIBLE_LOOKUP_MODE=HTTP
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 \
  -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=LIVE \
  --ingestion.work-directory=./var-csv-debug-live \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Use an approved test endpoint for this exercise. This command sends the consent JSON. Mode and lookup settings participate in the saved snapshot, so LIVE is a different run from the preview.

## 15. Step through the actual POST

**Breakpoints:** `FileProcessor.deliver()`, then [HttpUpsertClient.post()](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java).

After preparation, the run becomes DELIVERING. The processor reads staged requests in number order, skipping ACKNOWLEDGED requests. For request 1 it:

1. Applies the saved replay policy and retry budget.
2. Persists `SENDING` and increments attempts **before** the network call.
3. Parses the saved payload back into a JsonNode.
4. Calls `client.post(body, id + ":" + request.number(), settings)`.

Inspect `request.payload()`, `request.sourceRecords()`, the idempotency key `<run-id>:1`, and the saved delivery settings. The client sets content type and accept to application/json, optional bearer authorization and the configured idempotency header, then POSTs the JsonNode through Spring RestClient. Redirects are disabled.

No CSV parsing, enrichment or template rendering occurs inside the HTTP client. It sends the previously staged JSON structure.

## 16. Inspect acknowledgement, retry or failure

**Breakpoint:** `HttpUpsertClient.post()` inside the response callback, then `FileProcessor.deliver()` after `ledger.result(...)`.

| Result | Client classification | Processor behavior |
| --- | --- | --- |
| Configured successful status (default 200, 201, 204) | ACKNOWLEDGED | Save result; advance to next request. |
| Other 2xx | UNCERTAIN | Apply replay policy. |
| 408, 429, or status >= 500 | RETRYABLE | Apply replay policy. |
| ResourceAccessException, such as connection loss | UNCERTAIN | Apply replay policy. |
| Remaining HTTP status | REJECTED | Fail run and write HTTP_REJECTED report. |

Response bodies are neither used for per-entry success decisions nor persisted. The receiving API’s batch semantics must therefore match the configured acknowledgement rule.

With `replay-safe=false` (default), an unacknowledged attempt pauses the run rather than automatically resending it. If replay is explicitly enabled under a service-side idempotency agreement, retries reuse the saved JSON and key, with exponential delay capped at 30 seconds. Exhausting the attempt budget pauses the run. An interrupted retry delay also pauses it.

When every request is acknowledged, `finish()` archives the processing directory and sets COMPLETED. If archiving fails, FINALIZING remains recoverable without sending again.

## 17. Debug a restart or explicit resume

**Breakpoints:** `FileProcessor.recover()`, `resume()`, and the early status branches of `execute()`.

The poller automatically recovers STAGING, READY, DELIVERING and FINALIZING runs. It does not automatically retry PAUSED or FAILED runs. Explicit resume is:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.work-directory=./var-csv-debug-live \
  --ingestion.resume=YOUR_RUN_ID
```

Use the same work directory and supply the required runtime credentials. Resume reads the saved snapshot, not new mapping/delivery overrides. A STAGING recovery rereads the staged input and reuses already saved enriched rows. READY or DELIVERING recovery uses staged request bodies; FINALIZING recovery only finishes archiving.

A manual resume grants another attempt budget, but does not override `replay-safe=false`. If a previous delivery attempt exists under that saved policy, it pauses again pending service-side reconciliation. FAILED runs return false. Snapshot versions from removed processors are not interpreted by this release.

## 18. Trace one-contract and bounded multi-contract requests

Use the same debugger startup command but select `--ingestion.profile-id=flexible-contract-single-v1` and `--ingestion.file=examples/flexible/contract-batches.csv`, with a fresh work directory. Then repeat with `flexible-contract-array-v1`. Both default to PREVIEW; the LIVE lookup restrictions in section 14 still apply.

This fixture repeats `EXT-CONTRACT-A` for Alex and John and uses the other identity fields to resolve the correct member. Eight records cover three contracts and five members. See [the contract fixture guide](contract-batching.md) for identities, complete expected outputs and limits.

At `ProjectionEngine.project()` inspect:

```text
projection.scope()                          GROUPED
projection.batching().groupBy()              [resolved.owner.contract]
projection.batching().maxGroupsPerRequest()   1 (single) or 2 (array default)
projection.batching().maxRecordsPerGroup()    100
projection.batching().maxMembersPerGroup()    25
```

After the batching filter removes no-change rows, step into `RequestBatcher.partition()`. Its first-seen map gathers internal contract 123456789 from source rows 1, 2, 4 and 7; contract 222222222 from 3, 5 and 8; and contract 333333333 from 6. Member limits count distinct GUIDs; row limits include every selected coverage row. A contract over either limit throws before any upsert and is never split silently.

Single mode partitions these into three requests. Array mode puts the first two complete contracts into one request and the remaining contract into a second request. Step back into `projectOne()` / `render()` for each partition. The array template adds a root collection grouped by contract; each contract has `contractId` and its member-owned `consents`.

For John, payer group key `P2P-A` combines rows 2 and 4 into one GRANT consent with two coverages. Key `P2P-B` makes row 7 a second payer consent with RVOKE and one coverage. The provider group remains one PCDS entry. The optional source key is included in HTTP lookup criteria so a real facade can return that particular existing consent’s GUID; it is not an outgoing property.

Back at `ProcessingLedger.stage()`, inspect the request numbers and origins:

| Mode | Request 1 source records | Request 2 | Request 3 |
| --- | --- | --- | --- |
| Single | `[1,2,4,7]` | `[3,8,5]` | `[6]` |
| Array (limit 2) | `[1,2,4,7,3,8,5]` | `[6]` | None |

All bodies are prepared/staged before delivery. At `deliver()`, the key’s request-number suffix is now significant: if request 1 succeeds but 2 pauses, resume skips request 1 and reuses request 2’s saved JSON and key. It does not rerender a different set of contracts from an edited config. The in-memory file cap remains 1000; bounded HTTP requests do not make preparation an unlimited-file streaming implementation.

## Breakpoint checklist

| Stop | Inspect |
| --- | --- |
| `FileAdapterApplication.main` | File/resume arguments and loaded configuration |
| `ProfileCatalog.resolve` | Selected profile and matching rule |
| `FileProcessor.process` | Content hash, snapshot, run ID and consume flag |
| `DelimitedFileAdapter.read` | Headers, record number and string-valued data |
| `SourceNormalizer.normalize` | Physical versus canonical names |
| `ProjectionEngine.enrich` | Input contract and saved enriched payload |
| `ConsentMemberEnricher.enrich` | Criteria, owner, manager and consent GUIDs |
| `ConfiguredMemberResolver.resolve` | FIXTURE or HTTP resolution |
| `ProjectionEngine.project` / `RequestBatcher.partition` / `render` | Scope, complete-contract partitions, limits and final JsonNode |
| `ProcessingLedger.stage` | Complete saved body and source record list |
| `PreviewWriter.write` | Generated files in PREVIEW |
| `FileProcessor.deliver` | Saved payload, attempts and replay policy |
| `HttpUpsertClient.post` | URL, headers and returned outcome |
| `FileProcessor.finish` | Archive and terminal run status |
