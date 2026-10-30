# FileAdapter: code walkthrough and change guide

This is a Spring Boot / Gradle proof of concept for the adapter pattern and configurable file-to-JSON mapping. Consent is its worked example. File syntax, source names, enrichment, outgoing JSON structure and delivery are separate concerns.

The only processing strategy is now the flexible projection engine. Start with [application-flexible-demo.yml](../src/main/resources/application-flexible-demo.yml), the [runnable input matrix](formats.md), and [projection vocabulary](flexible-mappings.md).

## 1. Start the application

In Git Bash, from the repository root:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

The flexible demo is also the default Spring profile, so the explicit `--spring.profiles.active` argument is optional. The command processes a single file and exits. With no file or resume argument, the application stays running; the demo disables directory polling.

[FileAdapterApplication](../src/main/java/com/jenbrick/fileadapter/FileAdapterApplication.java) starts Spring, reads `IngestionProperties`, and calls:

```java
processor.process(properties.file(), properties.profileId(), false);
```

There are two uses of “profile”:

| Setting | Purpose |
| --- | --- |
| `spring.profiles.active=flexible-demo` | Load the Spring configuration file |
| `ingestion.profile-id=flexible-csv-v1` | Explicitly select a feed definition within `ingestion.profiles` |

Without a feed ID, routing uses the filename. A one-shot command copies its input; directory polling consumes a file only after durable staging and requires a producer-created `.ready` marker.

## 2. Select the feed definition

[ProfileCatalog](../src/main/java/com/jenbrick/fileadapter/config/ProfileCatalog.java) validates configured definitions at startup and selects exactly one definition for the filename or explicit ID.

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

This is a schematic fragment; copy the complete working configuration before editing it. Zero matching definitions or multiple matches cause an error. The CSV header pattern deliberately excludes `flexible-noheader.csv`.

[FileProcessor.process()](../src/main/java/com/jenbrick/fileadapter/processing/FileProcessor.java) copies the source locally, hashes its contents, saves the profile/delivery snapshot and creates a run. The run ID includes both content and configuration, rather than filename alone.

## 3. Select the physical parser: the adapter pattern

Every parser implements [FileFormatAdapter](../src/main/java/com/jenbrick/fileadapter/source/FileFormatAdapter.java):

```java
public interface FileFormatAdapter {
    Profile.Format format();
    void read(Path file, Profile.Layout layout,
              Consumer<SourceRecord> consumer) throws IOException;
}
```

[AdapterRegistry](../src/main/java/com/jenbrick/fileadapter/source/AdapterRegistry.java) receives Spring's list of implementations and selects one by `layout.format`. Duplicate registrations are rejected.

| Format | Implementation | Layout settings |
| --- | --- | --- |
| `DELIMITED` | `DelimitedFileAdapter` | Delimiter, header mode, positional columns |
| `FIXED_WIDTH` | `FixedWidthFileAdapter` | Record length, field offsets and lengths |
| `XML` | `XmlFileAdapter` | Record element path and namespaces |
| `JSON` | `JsonFileAdapter` | `ARRAY`, `OBJECT` or `LINES` input mode |

CSV, pipe, semicolon and tab files all use the same delimited adapter. Apache Commons CSV handles quoted delimiters and multiline quoted fields. In header mode, each value is addressed by header name; with no header, the configured positional names supply those keys.

Each parser emits `SourceRecord(number, payload)`, where `payload` is a Jackson tree. A CSV row might become:

```json
{
  "EXTERNAL_CONTRACT_ID": "EXTERNAL-001",
  "FIRST_NAME": "Alex",
  "LAST_NAME": "Example"
}
```

This intermediate representation describes the source. It is not the service request. Record numbers count logical data records, rather than physical text lines.

## 4. Normalize source names

[SourceNormalizer](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java) translates physical field names into logical names used by enrichment and projection.

For a producer that renames the external contract column:

```yaml
fields:
  EXTERNAL_CONTRACT_ID:
    column: CONTRACT_NUMBER
    aliases: [EXTERNAL_CONTRACT_ID, CONTRACT_ID]
```

This fragment belongs under `layout`. A nested XML/JSON value can be selected with a JSON Pointer, such as `/member/firstName`; a plain selector is a literal source property name.

**When `layout.fields` is populated, only its listed logical fields survive normalization.** Include every field needed by the downstream lookup and projection, including the unchanged ones. Without `fields`, the original parsed object passes through. Conflicting populated aliases are rejected.

## 5. Enrich the logical record

The example projection enables:

```yaml
enrichers: [consent-member]
```

[ProjectionEngine.enrich()](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) validates the input contract, reuses a saved enriched row if present, or invokes the selected [RecordEnricher](../src/main/java/com/jenbrick/fileadapter/projection/RecordEnricher.java) implementations in order.

[ConsentMemberEnricher](../src/main/java/com/jenbrick/fileadapter/projection/ConsentMemberEnricher.java) reads canonical uppercase source fields, calls [MemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/MemberResolver.java), validates the returned member identities, and adds:

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

The original logical fields remain alongside `resolved`. The namespace is reserved by this plugin. No consent preference means no member lookup for that row.

The demo uses synthetic fixtures. HTTP mode exercises a proposed lookup facade, not a discovered company API. Replace the resolver implementation to compose the real calls. Output nesting does not belong in the resolver.

The outgoing audit timestamp in the supplied template comes from `SOURCE_DATETIME`, preserving the sample's offset-free value. `resolved.sourceDateTime` is another available source, not an automatic override of that mapping. Generated per-row times need deliberate handling when rows will be grouped.

## 6. Build JSON from the projection template

[ProjectionEngine.project()](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) evaluates the configured root tree. Each node has exactly one constructor:

| Constructor | Meaning |
| --- | --- |
| `source` | Copy a logical/enriched value, object or array |
| `literal-json` | Insert a JSON constant |
| `object` | Build named child properties |
| `array` | Build collections from selected/grouped source rows |

For example, this replacement root:

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

builds a nested member object. With FILE scope, scalar values at that level must agree across all included rows; use RECORD scope for independently varying member objects.

[MappingValues](../src/main/java/com/jenbrick/fileadapter/mapping/MappingValues.java) handles defaults, transforms, blank policies and code translations. [TransformRegistry](../src/main/java/com/jenbrick/fileadapter/mapping/TransformRegistry.java) supplies trim, case conversion, date conversion and JSON scalar type conversion. Node `checks` and projection `input-contract` provide explicit required/type/nullability validation.

The tree is built with Jackson `ObjectNode`/`ArrayNode` values. There is no fixed Java consent request DTO to update when a property moves.

## 7. Filter and group the consent records

The working template uses `scope: FILE`. Its provider collection:

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

selects populated provider preferences and builds one provider entry per member. The actual profile adds the other fields and checks. A second collection creates payer consents. Inside each payer consent, a nested array groups coverages by payer ID and policy ID.

The three-row fixture produces two provider entries and one payer entry containing two coverages. Provider coverage is the literal `[]`. `consentManager` is a sibling of `transactionDetails` in the primary example.

Every mapped scalar within a group must agree after transformation. Conflicting statuses, dates or duplicated coverage details fail preparation; there is no implicit first-row winner. Collections preserve first-seen group order.

`scope: RECORD` instead builds one request per row and cannot combine coverage across rows. Scope controls request boundaries; root object versus array controls JSON shape. They are independent.

## 8. Stage, preview or deliver

After the entire file passes preparation, `FileProcessor` saves the final body and contributing source record numbers. PREVIEW writes:

```text
var-flexible-demo/preview/<run-id>/<input-file>.preview.json
var-flexible-demo/preview/<run-id>/requests.jsonl
```

The preview includes source metadata and request details. JSON Lines contains one complete compact JSON request body per physical line. A body can itself be an object or an array. Its filename does not imply JSON Lines is sent over HTTP.

LIVE calls [HttpUpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java), which posts each staged body as `application/json`. It does not choose consent-specific fields. Enable LIVE only with the intended lookup and service contracts configured.

## 9. Change an existing file layout

| Producer change | Application change |
| --- | --- |
| Reorder header-based CSV columns | None: names address fields |
| Rename a header | Change normalization selectors/aliases, or projection sources if no normalizer is used |
| Add an unused column | Usually none |
| Remove a required input field | Replace/remove its downstream dependencies or reject the feed |
| Reorder headerless fields | Update `layout.columns` in exact physical order |
| Change fixed-width positions | Update offsets, lengths and total record length |
| Move XML records | Change `layout.xml.record-path` |
| Move nested XML/JSON fields | Change `layout.fields` JSON Pointers |
| Change input JSON packaging | Select `OBJECT`, `ARRAY` or `LINES` |

Fixed-width offsets are zero-based Unicode code-point offsets. The fixture uses 34 fields of 32 characters each: 1,088 characters per record. Trim padding in mappings where appropriate.

A JSON wrapper such as `{"members":[...]}` is one object in OBJECT mode; the adapter does not automatically expand its array into source records. That would require an explicit input record-selector feature.

## 10. Add a new delimiter

The repository includes a runnable semicolon profile:

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

No new Java adapter is needed. `*memberLookup` and `*consentProjection` refer to anchors defined earlier in the same YAML document. For tab, use the YAML double-quoted value `"\t"`.

For no-header files, set `header-mode: NONE` and supply the complete ordered `columns` list. Examples for both CSV and pipe are checked in. Do not use an abbreviated column list against the full consent fixtures.

Current configuration accepts one delimiter character and fixed CSV quoting rules. Multi-character delimiters or different escaping rules require extending the adapter/configuration.

A genuinely new syntax, such as Excel, requires a format enum value, a Spring `FileFormatAdapter` implementation, layout validation/settings and parser tests. Its emitted `SourceRecord` objects enter the same pipeline.

## 11. Add a contract ID at the top level

To produce an object containing `contractId` and `consents`, replace the root with:

```yaml
root:
  object:
    contractId:
      source: resolved.owner.contract
      required: true
    consents: *consentArray
```

This selects the internal resolved contract rather than the external source identifier. Run the checked-in variant:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.profile-id=flexible-contract-envelope-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

Compare with [expected-contract-envelope.json](../examples/flexible/expected-contract-envelope.json). JSON object property order has no semantic significance; consumers must address `contractId` by name.

With FILE scope, all rows must agree on that one contract ID. Multiple contracts require a grouped collection or separate requests. The POC has FILE and RECORD scopes; it does not yet offer one-request-per-contract scope.

## 12. Add, remove, rename or move attributes

To add `planType` to coverage, put this node in the payer coverage item's `object`:

```yaml
planType:
  source: PAYER_PLAN_TYPE
  transforms: [trim]
  blank-policy: OMIT
```

Use `blank-policy: 'NULL'` to send null for blank text. Remove a node to stop emitting that property, and update any checks referring to it. Rename its object key to rename the output property. Move its complete node to change nesting.

The existing owner node copies an entire object:

```yaml
consentOwner:
  source: resolved.owner
```

To rename its properties or exclude PIN, replace it with explicit children:

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

That changes only output assembly. The lookup continues returning its existing canonical identity. Changing how an identity is obtained belongs in enrichment.

For a typed constant:

```yaml
schemaVersion:
  literal-json: '"2"'
```

`'"2"'` is a JSON string; `'2'` is a JSON number. `literal-json: 'null'` explicitly emits null. Optional missing sources are otherwise omitted, unless `default-json` supplies a fallback.

## 13. Make the whole HTTP body an array

The existing `flexible-root-array-v1` variant replaces `{"consents":[...]}` with `[...]`:

```yaml
projection:
  <<: *consentProjection
  version: flexible-root-array-v1
  skip-when-empty: "$"
  root: *consentArray
```

`skip-when-empty` now checks the root itself instead of the removed wrapper property. Run:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --ingestion.profile-id=flexible-root-array-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

This is one POST with an array body, not one POST per element. Compare [expected-root-array.json](../examples/flexible/expected-root-array.json).

`flexible-reshaped-v1` is another configuration-only variant: it moves `consents` to `payload.entries`, nests manager inside transaction details, and moves coverage to `payerInfo.coverages`. These demonstrate changing contracts; they do not assert that an existing endpoint accepts them.

## 14. Iterate without rebuilding Java

Copy the complete working YAML to `config/feed.yml`, edit that copy, and run:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.config.additional-location=file:./config/feed.yml \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

External YAML changes require a restart, not a rebuild. Spring replaces profile lists rather than merging individual entries; include the full list needed for that run. Anchors stay within one YAML document. Packaged YAML or Java changes require rebuilding the JAR.

Keep an input fixture and expected body for each approved mapping. Increment meaningful profile/projection versions, run PREVIEW, and compare bodies before using LIVE. A changed configuration creates a different run identity; it is not a continuation of an earlier LIVE run. Resuming a run deliberately uses its saved configuration and body.

## 15. Why snapshots remain

The current implementation retains a file-backed H2 ledger under the work directory. It is not an in-memory database. It saves completed enrichment and final request bodies so a restart/retry can reuse them instead of looking up changing data or generating different timestamps.

Snapshots do not lock member records, prevent future updates or guarantee exactly-once delivery. The upsert service must define idempotency and batch acknowledgement. Replay safety defaults to false.

Persistence could be separated behind a store interface, replaced with atomic files/a company database, or omitted for a mapping-only demonstration. These are possible extensions; this cleanup does not introduce a persistence-off setting.

The POC prepares a bounded collection in memory: 1,000 records in the example, with an allowed limit up to 10,000. It is one worker with a local durable work directory, not an unbounded streaming aggregation engine.

## Cleanup and existing runs

The original employee mappings, specialized consent request planner/store, old fixtures, old mock/generator scripts, consent-demo configuration and superseded PowerPoint have been removed. Markdown and the flexible examples are authoritative. Generic parser, validation, delivery and recovery functionality remains.

Projection snapshots use engine version 4. Previously saved flexible snapshots remain readable; ignored obsolete configuration fields do not restore the removed processors. Engine versions 1–3 cannot be resumed by this version. Use the earlier Git revision to resolve those runs with their original behavior. Keep old work directories until any delivery status has been reconciled; do not automatically re-submit their source files in LIVE.
