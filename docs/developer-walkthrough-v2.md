# FileAdapter: follow the code and make changes

> V2: plain-English copy. [Original for comparison](developer-walkthrough.md). Commands, configuration examples and diagrams are unchanged.
This Spring Boot/Gradle POC reads files, optionally looks up more information, builds JSON from settings and sends it over HTTP. Consent is the example. Each part has a separate job so a change to file syntax does not have to change service calls or JSON structure.

The flexible projection engine is the only current processing path. Start with the [working YAML](../src/main/resources/application-flexible-demo.yml), [input examples](formats-v2.md) and [mapping reference](flexible-mappings-v2.md).

For one or several complete contracts per request, see [contract batching](contract-batching-v2.md). The whole-file examples below are still useful comparisons. For a debugger session, use the [CSV walkthrough](csv-debug-walkthrough-v2.md) and [runtime diagrams](runtime-diagrams-v2.md).

## 1. Start the application

From the repository root in Git Bash:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

`flexible-demo` is also the default Spring profile. This command processes one file and exits. Without a file or resume argument, the application stays running; the demo turns polling off.

[FileAdapterApplication](../src/main/java/com/jenbrick/fileadapter/FileAdapterApplication.java) starts Spring, reads `IngestionProperties` and calls:

```java
processor.process(properties.file(), properties.profileId(), false);
```

“Profile” refers to two different settings:

| Setting | What it selects |
| --- | --- |
| `spring.profiles.active=flexible-demo` | The Spring configuration file |
| `ingestion.profile-id=flexible-csv-v1` | A feed definition inside `ingestion.profiles` |

Without an explicit feed ID, use filename matching. A one-file command copies its input. Polling requires the producer’s `.ready` marker and removes the incoming file only after saving a local working copy.

## 2. Choose the feed definition

[ProfileCatalog](../src/main/java/com/jenbrick/fileadapter/config/ProfileCatalog.java) checks all configured definitions at startup. It selects one by filename or explicit ID.

```yaml
- id: flexible-csv-v1
  filename-pattern: flexible-consents{,-header*}.csv
  layout:
    charset: UTF-8
    format: DELIMITED
    delimiter: ','
    header-mode: FIRST_RECORD
  consent: # optional lookup settings for the example enricher
  projection: # output template and processing scope
```

This is a shortened example; copy the full working configuration before editing it. No matches or several matches cause an error. The headered CSV pattern deliberately does not match `flexible-noheader.csv`.

[FileProcessor.process()](../src/main/java/com/jenbrick/fileadapter/processing/FileProcessor.java) copies the input, hashes its contents and saves the chosen profile/delivery settings. Content and configuration identify the run, not filename alone.

## 3. Choose the file reader

All readers implement [FileFormatAdapter](../src/main/java/com/jenbrick/fileadapter/source/FileFormatAdapter.java):

```java
public interface FileFormatAdapter {
    Profile.Format format();
    void read(Path file, Profile.Layout layout,
              Consumer<SourceRecord> consumer) throws IOException;
}
```

[AdapterRegistry](../src/main/java/com/jenbrick/fileadapter/source/AdapterRegistry.java) gets the Spring implementations and selects one from `layout.format`. Duplicate registrations fail.

| Format | Class | Main settings |
| --- | --- | --- |
| `DELIMITED` | `DelimitedFileAdapter` | Delimiter, header mode and ordered columns |
| `FIXED_WIDTH` | `FixedWidthFileAdapter` | Record length, field offsets/lengths |
| `XML` | `XmlFileAdapter` | Record path and namespaces |
| `JSON` | `JsonFileAdapter` | `ARRAY`, `OBJECT` or `LINES` |

CSV, pipe, semicolon and tab share the delimited reader. Apache Commons CSV handles quoted delimiters and quoted fields spanning lines. Headered values use their header names; headerless values use configured column names in physical order.

A reader returns `SourceRecord(number, payload)`, with a Jackson object as payload:

```json
{
  "EXTERNAL_CONTRACT_ID": "EXTERNAL-001",
  "FIRST_NAME": "Alex",
  "LAST_NAME": "Example"
}
```

This is the input record represented in memory, not an HTTP request. Numbers count logical data records rather than physical text lines.

## 4. Put input fields into a consistent form

[SourceNormalizer](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java) translates producer names into the logical names used by lookups and JSON mapping.

For a renamed contract column:

```yaml
fields:
  EXTERNAL_CONTRACT_ID:
    column: CONTRACT_NUMBER
    aliases: [EXTERNAL_CONTRACT_ID, CONTRACT_ID]
```

Put this under `layout`. A JSON Pointer such as `/member/firstName` selects a nested XML/JSON value. A plain selector names a literal property.

**When `layout.fields` is configured, only listed fields remain.** Include everything needed later, even unchanged fields. Without it, the parsed object passes through. Conflicting populated aliases fail.

## 5. Look up group and member information

The consent example enables:

```yaml
enrichers: [group-context, consent-member]
```

[ProjectionEngine.enrich()](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) checks required input/type rules and reuses a saved enriched row if available. Otherwise it runs the named [RecordEnricher](../src/main/java/com/jenbrick/fileadapter/projection/RecordEnricher.java) steps in order.

[GroupEnricher](../src/main/java/com/jenbrick/fileadapter/projection/GroupEnricher.java) sends `EXTERNAL_GROUP_ID` to the group service and saves internal group number/customer ID under `resolvedGroup`.

[ConsentMemberEnricher](../src/main/java/com/jenbrick/fileadapter/projection/ConsentMemberEnricher.java) sends only member demographics/identifiers plus that context through [MemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/MemberResolver.java). It checks the identities and adds:

```json
{
  "resolved": {
    "owner": {
      "contract": "123456789",
      "pin": "001",
      "contractMemberGuid": "MEMBER-GUID-001"
    },
    "manager": {
      "contract": "123456789",
      "pin": "001",
      "contractMemberGuid": "MEMBER-GUID-001"
    },
    "providerConsentGuid": null,
    "payerConsentGuid": null,
    "sourceDateTime": "2026-10-30T12:00:00Z"
  }
}
```

Original logical fields remain alongside `resolvedGroup` and `resolved`; those two names are reserved. A row with neither preference needs neither lookup. Group results are shared within the run by external group ID; member-enriched results are saved per row.

Demo lookups use synthetic fixtures. HTTP clients show small POC formats to adapt to the company APIs. The member request has no consent/payer fields and does not find a payer consent by key. See [lookup details](group-member-lookups-v2.md). Neither resolver chooses the outgoing JSON nesting.

The supplied template reads the outgoing timestamp from `SOURCE_DATETIME`, retaining the sample’s offset-free value. `resolved.sourceDateTime` is available but does not override it. If generated times will be grouped across rows, decide how they should agree.

## 6. Build JSON from the template

[ProjectionEngine.project()](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) follows the configured root tree. Every node chooses exactly one way to create a value:

| Constructor | What it does |
| --- | --- |
| `source` | Copy a logical/enriched value, object or array |
| `literal-json` | Insert a JSON constant |
| `object` | Build named properties |
| `array` | Build collections from selected/grouped rows |

For example:

```yaml
root:
  object:
    member:
      object:
        firstName:
          source: FIRST_NAME
          transforms: [trim]
        contractId:
          source: resolved.owner.contract
          required: true
        active:
          literal-json: 'true'
```

This builds a nested member object. In FILE mode, scalar values at this level must agree across included rows. Use RECORD mode for separate member objects that vary by row.

[MappingValues](../src/main/java/com/jenbrick/fileadapter/mapping/MappingValues.java) handles defaults, conversions, blank rules and code translations. [TransformRegistry](../src/main/java/com/jenbrick/fileadapter/mapping/TransformRegistry.java) provides trimming, case/date conversions and scalar types. `checks` validates constructed nodes; `input-contract` validates input before lookup.

Jackson `ObjectNode`/`ArrayNode` build the result. There is no fixed Java consent request DTO to edit whenever a property moves.

## 7. Select and combine consent rows

The basic template uses `scope: FILE`. Its provider collection starts with:

```yaml
array:
  - where:
      source: PROVIDER_CLINICAL_CONSENT_PREFERENCE
      present: true
    group-by: [resolved.owner.contractMemberGuid]
    item:
      object:
        consentTarget: { literal-json: '"PCDS"' }
        consentStatus: { source: PROVIDER_CLINICAL_CONSENT_PREFERENCE }
```

It selects populated provider preferences and creates one provider consent per member. The full profile adds remaining fields/checks. A second collection builds payer consents, with nested coverages grouped by payer ID and policy ID.

Three input rows produce two provider consents and one payer consent with two coverages. Provider coverage is `[]`. The main example places `consentManager` beside `transactionDetails`.

After conversion, every mapped value in a group must agree. Conflicting statuses, dates or coverage details fail preparation. The engine does not pick the first or last row. Groups follow first appearance.

`RECORD` makes one request per row and cannot combine coverage across rows. `GROUPED` gathers all rows for a contract, checks its row/member limits and packs up to the configured number of contracts per request. `RequestBatcher.partition()` does that grouping. Request boundaries and whether the JSON root is an object or array are separate choices.

## 8. Save requests, then preview or send

After file preparation succeeds, `FileProcessor` saves each complete body and contributing record numbers. PREVIEW writes:

```text
var-flexible-demo/preview/<run-id>/<input-file>.preview.json
var-flexible-demo/preview/<run-id>/requests.jsonl
```

The named preview contains metadata and requests. `requests.jsonl` has one complete compact body per line; that body can be an object or array. The application does not send the JSON Lines file as the HTTP body.

LIVE calls [HttpUpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java), which sends saved JSON as `application/json`. It does not choose consent fields. Configure compatible lookups and the receiving endpoint before using LIVE.

## 9. Change a file layout

| Input change | What to update |
| --- | --- |
| Reorder headered CSV columns | Nothing if names are unchanged |
| Rename a header | Normalization selector/alias, or mapping source if no normalizer |
| Add an unused column | Usually nothing |
| Remove a required field | Replace/remove dependent rules or reject the feed |
| Reorder headerless fields | `layout.columns` in exact order |
| Move fixed-width fields | Offsets, lengths and total record length |
| Move XML records | `layout.xml.record-path` |
| Move nested XML/JSON fields | `layout.fields` JSON Pointers |
| Change input JSON packaging | `OBJECT`, `ARRAY` or `LINES` |

Fixed-width offsets start at zero and count Unicode code points. The example has 34 fields of 32 characters, or 1,088 per record. Trim padding where needed.

`{"members":[...]}` is one object in OBJECT mode. It does not automatically expand into several source records; that would need an explicit record-selection feature.

## 10. Add a delimiter or file syntax

The semicolon profile shows a configuration-only change:

```yaml
- id: flexible-semicolon-v1
  filename-pattern: flexible*.scsv
  layout:
    charset: UTF-8
    format: DELIMITED
    delimiter: ';'
    header-mode: FIRST_RECORD
  consent: *memberLookup
  projection: *consentProjection
```

`*memberLookup` and `*consentProjection` refer to anchors earlier in the same YAML document. For tab, use the double-quoted YAML value `"\t"`.

For headerless files, set `header-mode: NONE` and list every column in order. CSV and pipe examples are included. A shortened list will not work with the full fixtures.

The adapter accepts one delimiter character with fixed CSV quoting rules. Several-character delimiters or different escaping need code/settings changes.

A new syntax such as Excel needs a format enum, Spring `FileFormatAdapter`, layout validation/settings and parser tests. Its `SourceRecord` objects use the same downstream flow.

## 11. Add a top-level contract ID

Use this root for `contractId` and `consents`:

```yaml
root:
  object:
    contractId:
      source: resolved.owner.contract
      required: true
    consents: *consentArray
```

It reads the resolved internal contract, not the input’s external ID. Run the included profile:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.profile-id=flexible-contract-envelope-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Compare [expected-contract-envelope.json](../examples/flexible/expected-contract-envelope.json). JSON object property order does not matter; consumers read by name.

In FILE mode, all rows must agree on one contract ID. For several contracts, use a grouped collection or separate requests. `GROUPED` with `batching.max-groups-per-request: 1` gives one complete contract per request. A higher count with a contract-grouped root array gives several. See [both profiles](contract-batching-v2.md).

## 12. Add, remove, rename or move a field

Add `planType` inside the payer coverage item’s `object`:

```yaml
planType:
  source: PAYER_PLAN_TYPE
  transforms: [trim]
  blank-policy: OMIT
```

`blank-policy: 'NULL'` sends null for blank text. Delete a node to stop sending the field and update any checks using it. Change its object key to rename it. Move the whole node to change nesting.

The current owner mapping copies the whole object:

```yaml
consentOwner:
  source: resolved.owner
```

To rename fields or leave out PIN, name the children explicitly:

```yaml
consentOwner:
  object:
    contractId:
      source: resolved.owner.contract
      required: true
    memberGuid:
      source: resolved.owner.contractMemberGuid
      required: true
```

This changes JSON building only. Lookup still returns its usual internal identity. Change enrichment when the way we obtain that identity needs to change.

A typed constant looks like:

```yaml
schemaVersion:
  literal-json: '"2"'
```

`'"2"'` creates a JSON string; `'2'` creates a number. `literal-json: 'null'` creates null. Optional missing sources are omitted unless `default-json` provides a fallback.

## 13. Make the HTTP body an array

`flexible-root-array-v1` changes `{"consents":[...]}` to `[...]`:

```yaml
projection:
  <<: *consentProjection
  version: flexible-root-array-v1
  skip-when-empty: "$"
  root: *consentArray
```

`skip-when-empty` now checks the root rather than a removed wrapper. Run it:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.profile-id=flexible-root-array-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

This is one POST containing an array, not one POST per array item. Compare [expected-root-array.json](../examples/flexible/expected-root-array.json).

`flexible-reshaped-v1` also uses only YAML: `consents` moves to `payload.entries`, manager moves into transaction details, and coverage becomes `payerInfo.coverages`. These are examples of possible changes, not accepted endpoint contracts.

## 14. Try mapping changes without rebuilding Java

Copy the full working YAML to `config/feed.yml`, edit it and run:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.config.additional-location=file:./config/feed.yml \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

External YAML needs a restart, not a rebuild. Spring replaces profile lists instead of merging their entries, so include the full needed list. YAML anchors work only inside the same document. Rebuild for Java or packaged-resource changes.

Keep an input and expected body for every agreed mapping. Version meaningful changes, run PREVIEW and compare bodies before LIVE. New configuration means a different run ID, not continuation of an old LIVE run. Resume intentionally uses the saved configuration/body.

## 15. Why we save snapshots

The work folder contains H2 on disk, not an in-memory database. It saves successful lookup work and exact request bodies so restart does not use newly changed lookup data or timestamps.

Snapshots do not lock members, block future updates or guarantee delivery happens exactly once. The service must agree how acknowledgement and duplicate-safe retry work. `replay-safe` defaults to false.

Storage could later use an interface backed by company storage or atomic files. A mapping-only demo could be designed without it. There is currently no persistence-off setting.

The POC prepares a limited-size file in memory: 1,000 records in examples, configurable up to 10,000. It uses one worker and a local saved work folder. Unlimited-file streaming is separate work.

## Old code and saved runs

The old employee mappings, consent-specific planner/store, fixtures, mock/generator scripts, consent-demo config and PowerPoint were removed. Use Markdown and flexible examples. Generic parsing, validation, delivery and recovery remain.

Current projections use engine version 4. Earlier flexible snapshots remain readable, but ignored old fields do not restore removed processors. Engine versions 1–3 need the earlier Git revision. Keep old work folders until delivery outcomes are settled; do not automatically submit their files again in LIVE.

## Results and service failures

`FileProcessor` saves original `SourceRecord` values and fixed-width raw text through `ResultStore` before normalization. `PreparationCoordinator.prepare()` applies error rules, runs group/member lookups, optionally rejects contracts and returns mapped requests. Without error settings, failures remain HARD. With MEDIUM handling, rejected contracts are removed before final batching.

`HttpUpsertClient` normally acknowledges by status. Optional `SCOPED_V1` validates completed service results. `ProcessingLedger` saves issues with acknowledgement before `ServiceResultProcessor` matches them to source rows. A reliably identified member rejection does not automatically reject the contract.

`ResultPublisher` and `SourceResultWriter` write IT diagnostics and original-format customer rows with code/remarks. Outside processes send them. Partial completion uses `_WITH_ERRORS`; warnings alone do not stop processing. See [the result guide](error-handling-v2.md).
