# Build JSON from mapping settings

> V2: plain-English copy. [Original for comparison](flexible-mappings.md). Commands, configuration examples and diagrams are unchanged.
The POC keeps file reading, lookups, JSON building and HTTP sending separate. Consent is the worked example; the mapping engine is not tied to that one API.

The [working YAML](../src/main/resources/application-flexible-demo.yml) recreates the [October 29 sample](../examples/json-sample/CONSENT%20JSON%20ARRAY.txt): an object with `consents`, provider `coverage: []`, payer coverage arrays and a `consentManager` on each consent entry.

## Run the example

From the repository root in Git Bash:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

The profile turns polling off, uses synthetic group/member fixtures and runs PREVIEW. Find `flexible-consents.csv.preview.json` in the logged run folder under `var-flexible-demo`. Its request body should match [expected-envelope.json](../examples/flexible/expected-envelope.json).

Three input rows describe two members. The second member has two payer rows. Grouping produces two provider consents and one payer consent with two coverages, rather than repeating the provider consent for every row.

The fixture adds four audit fields after the original 30: `PROVIDER_SOURCE_SYSTEM`, `PAYER_SOURCE_SYSTEM`, `SOURCE_DATETIME` and `SOURCE_USER`. They make the sample’s MDB/XYZ values and offset-free timestamp explicit. They are examples, not required columns for every feed. A real feed can use selectors, defaults, constants or enrichment for that information.

## What can change in settings

| Change | Setting |
| --- | --- |
| Add/rename an outgoing field | An `object` property |
| Move consent manager | Move its `source: resolved.manager` node |
| Rename the wrapper or use a root array | `projection.root` |
| Send a constant, null, empty object or array | `literal-json` |
| Rename an input column | `layout.fields` selectors/aliases or the node’s `source` |
| Convert a value or translate a code | `transforms`, `values`, `blank-policy` |
| Combine rows for one member | Collection `group-by` |
| Gather coverages under one consent | A nested `array` collection |
| Include only rows with a preference | Collection `where` |
| Choose which records go into each request | `RECORD`, `FILE` or `GROUPED` scope and `batching` |

A new input syntax needs a file adapter. A new lookup or calculation needs a `RecordEnricher` or `ValueTransform`. Changing the template does not need a consent-specific Java builder.

## The template’s building blocks

A projection has a version, scope, record limit, optional enrichers and a root tree describing the JSON. Each node chooses **exactly one** constructor:

```yaml
# A value from the normalized/enriched record
source: FIRST_NAME
required: true
transforms: [trim]
```

```yaml
# A typed constant, supplied as JSON text
literal-json: 'null'
```

```yaml
# A nested JSON object
object:
  person:
    object:
      firstName: { source: FIRST_NAME, required: true, transforms: [trim] }
      active: { literal-json: 'true' }
```

```yaml
# An array assembled from the current collection of rows
array:
  - where: { source: PAYER_CONSENT_PREFERENCE, present: true }
    group-by: [resolved.owner.contractMemberGuid]
    item:
      object:
        consentTarget: { literal-json: '"P2PDS"' }
        consentOwner: { source: resolved.owner, required: true }
```

`source` reads simple dotted paths and can copy an entire object/array, such as a resolved identity. Use collection rules for nested arrays rather than expressions in field names.

### Reading and converting a value

- `required` rejects missing, null or blank values.
- `default-value` supplies a string only when a field is missing.
- `default-json` supplies a typed fallback when an optional value is omitted: missing, or blank with OMIT. It runs after scalar mapping and preserves explicit null. Do not combine it with `required` or `default-value`. The sample uses `'null'` for missing consent GUIDs.
- `transforms` run in listed order, before blank handling.
- `blank-policy` can be OMIT, NULL, EMPTY or REJECT. Default is OMIT.
- `values` translates allowed codes. Unknown codes fail.
- An explicit optional null stays null and skips conversions.

A blank date/number can fail conversion before blank rules apply. Use a suitable transform if optional blanks need special treatment.

`literal-json` creates typed constants: `'[]'`, `'{}'`, `'null'`, `'123'` or `'"MDB"'`. It must contain exactly one valid JSON value, without duplicate object keys.

An optional missing property is left out. A source node can copy all of `resolved.owner`, so moving it elsewhere in JSON is just a template change.

### Check inputs and constructed JSON

Node `checks` use a dotted path relative to that node, JSON type, required flag and nullable flag. `input-contract` checks normalized input before enrichment. These are specific field/type checks, not full JSON Schema support.

```yaml
input-contract:
  - { path: PROVIDER_CLINICAL_CONSENT_PREFERENCE, type: STRING, required: true, nullable: true }
```

### Select and group rows

An array can have several collection rules. They append results in configured order, and groups follow first appearance. The sample builds provider groups before payer groups.

Supported `where` forms:

```yaml
where: { source: PREFERENCE, present: true }
```

```yaml
where: { source: RECORD_TYPE, equals-value: MEMBER }
```

```yaml
where:
  any-present: [PAYER_ID, PAYER_POLICY_ID, PAYER_GROUP_NUMBER]
```

“Present” means not missing/null/blank for text and not empty for containers. Equality is exact. Normalize source codes first when they vary. `any-present` keeps partly populated coverage rows so required fields/keys can reject them instead of silently dropping them.

Without `group-by`, each selected row creates an item. With it, rows sharing the configured nonblank scalar keys form one item. Mapped values in that group must agree **after conversion**. Conflicting status, date, manager or other values fail preparation. There is no first-row/last-row winner.

A nested collection sees only its parent group’s rows. The basic sample groups payer consents by member GUID and coverages by payer ID/policy ID. Identical repeats combine; conflicting details for the same key fail. Replace these sample keys with agreed business keys. Group keys use source values directly, so normalize padding/case when needed.

## Try different outgoing JSON

Use the same CSV with an array as the whole body:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-root-array-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

This reuses the consent collection as the root. Compare [expected-root-array.json](../examples/flexible/expected-root-array.json).

Try different nesting:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-reshaped-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

YAML alone moves `consents` to `payload.entries`, manager into `transactionDetails`, and coverage to `payerInfo.coverages`. Compare [expected-reshaped.json](../examples/flexible/expected-reshaped.json). This shows what can change; it does not mean the endpoint accepts this format.

A simple example unrelated to consent:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-record-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

This has no lookups. It maps names into `person.firstName` / `person.lastName` and produces three separate requests.

## Try different input formats

The [format guide](formats-v2.md) lists all 11 layouts, profile IDs and expected outputs. Run them and the output variants:

```bash
bash scripts/run-examples.sh
```

After building the JAR, compare generated JSON with expected files:

```bash
python scripts/verify-examples.py
```

Examples cover CSV/pipe with and without headers, semicolon, tab, fixed width, XML, JSON array, JSON object and JSON Lines. The single-object JSON example has one member; the others have the equivalent three-row data. Fixed width uses 32-character fields. XML selects `/members/member`. Input JSON packaging is independent of the outgoing root shape.

## Add information through enrichment

`RecordEnricher` is a named Spring extension:

```java
public interface RecordEnricher {
    String name();
    SourceRecord enrich(SourceRecord source, Profile profile, Mode mode);
    // Optional run-aware override for durable lookup context:
    default SourceRecord enrichForRun(String runId, SourceRecord source, Profile profile, Mode mode) {
        return enrich(source, profile, mode);
    }
}
```

Configure `enrichers: [group-context, consent-member]`.

`GroupEnricher` reads `profile.group`, calls `GroupResolver` with `EXTERNAL_GROUP_ID` and adds `resolvedGroup.internalGroupNumber` / `resolvedGroup.customerId`. `ConsentMemberEnricher` reads `profile.consent`, sends a typed member-only payload with that context through `MemberResolver`, and adds `resolved.owner`, `resolved.manager` and optional legacy consent GUIDs.

Both namespaces are reserved. The template decides how to use those values in JSON. `consent-member` expects the uppercase logical input names shown in the sample. Use `layout.fields` to translate other source names. Another business domain can register its own enricher and omit `profile.consent`. Enrichers must return an object with the same record number and keep service calls read-only.

Fixture mode is for the demo. LIVE refuses fixtures at either stage. HTTP group lookup uses `GROUP_LOOKUP_MODE=HTTP` and `GROUP_LOOKUP_URL`; member lookup uses `FLEXIBLE_LOOKUP_MODE=HTTP` and `CONSENT_LOOKUP_URL`. Tokens are `GROUP_LOOKUP_BEARER_TOKEN` and `CONSENT_LOOKUP_BEARER_TOKEN`. These POC interfaces need adaptation to company APIs; see [lookup payloads](group-member-lookups-v2.md).

`UPSERT_URL` / `UPSERT_BEARER_TOKEN` separately control consent delivery. Integration tests exercise lookup and delivery separately.

## Requests, saved progress and limits

| Setting or saved information | What it means |
| --- | --- |
| `FILE` | Map the selected file into one request. The original multi-member example uses this. |
| `RECORD` | Map each row separately; cannot combine coverages across rows. |
| `GROUPED` | Keep complete groups together and put up to the configured number into a request. Contract examples use this. |
| `skip-when-empty` | A dotted result path, or `$` for the root. Missing/null/empty container means no request. The main example checks `consents`. |
| Group results | Save a successful result once per run/external group, before member lookup completes. |
| Enriched rows | Save in H2 before building JSON. Interrupted STAGING reuses saved rows. A read-only lookup may repeat if the crash came before saving. |
| Request bodies | Save exact JSON before delivery. READY/resume uses that body without new lookup/mapping. |
| Source references | Keep all contributing record numbers. Aggregate previews/errors use `sourceRecordNumbers`; a single-row request uses the singular field. |
| Configuration | Save an unchanged snapshot for recovery. Current engine version is 4. Earlier flexible snapshots remain readable; versions 1–3 need the previous release. |
| File limit | `max-records` from 1 to 10,000; examples use 1,000. Exceeding it fails before delivery. |
| Template limit | Up to 32 nested levels and 1,024 configured nodes. |

Even RECORD mode currently prepares the supported-size file in memory before sending. Unlimited-file streaming/chunking is separate work. The engine does not support scripts, arbitrary expressions, unrelated-source joins or distributed transactions.

The service must define transaction behavior and acknowledge the complete selected body. Turn on replay only when it guarantees duplicate-safe retries.

## Work on a real feed

Copy the complete YAML to an external file and add `--spring.config.additional-location=file:./config/feed.yml`. Include the full needed profile list because Spring replaces lists. Restart after edits; mappings do not hot-reload. Rebuild only for Java or packaged-resource changes.

Version meaningful rule changes. Keep input/expected-output files, run PREVIEW and inspect changes before LIVE. The [developer walkthrough](developer-walkthrough-v2.md) connects settings to classes.

Tests cover parsing, conversions, required/type/null checks, exact sample output, alternate JSON, conflicts, saved lookups and HTTP retry. The packaged verifier compares layouts and output variants with expected JSON. See [verification history](verification-v2.md).

## GROUPED: keep requests to a limited number of contracts

See [contract batching](contract-batching-v2.md) for commands, JSON and the difference between a payer consent and its coverages. Both profiles are in the demo YAML.

```yaml
scope: GROUPED
max-records: 1000
batching:
  group-by: [resolved.owner.contract]
  max-groups-per-request: 2
  max-records-per-group: 100
  member-key: resolved.owner.contractMemberGuid
  max-members-per-group: 25
  where:
    any-present: [PROVIDER_CLINICAL_CONSENT_PREFERENCE, PAYER_CONSENT_PREFERENCE]
```

`batching` is allowed only with GROUPED. It needs scalar grouping paths, a maximum groups/request from 1 to 10,000, and a maximum rows/group from 1 through `max-records`. Optional `member-key` and `max-members-per-group` must be supplied together. The member cap is 1 through the row cap. `where` uses collection-filter syntax. No-change rows still count against the file limit.

The batcher gathers rows by first-seen group key, including rows scattered across the file. It never splits a group. It rejects oversized groups before rendering, packs up to the maximum groups and keeps the final smaller batch.

Each request is then rendered and checked separately, including scalar agreement, `skip-when-empty`, source references and node checks. By default a later rendering error still stops all delivery during preparation. The optional MEDIUM policy below can reject contracts first.

The single-contract root uses `resolved.owner.contract` for `contractId`. The array root groups by the same path and builds that contract object for each group. It needs this nested grouping because distinct contracts cannot agree on a single scalar contract ID.

The new payer template also groups by `PAYER_CONSENT_KEY` within the member. That key stays in mapping; it is not sent to demographics or automatically emitted. It is required for these multi-consent templates, not the older one-payer-consent examples. Existing consent GUIDs need a separate consent-identity lookup after resolving the member.

`CONTRACTS_PER_REQUEST` controls array-demo contract count. `CONTRACT_MAX_ROWS` / `CONTRACT_MAX_MEMBERS` cap a contract. They count records/items, not JSON bytes. File preparation is still limited and in memory.

## Error rules and customer results are separate from outgoing JSON

Profile `errors` settings control validation rules, severity, approved wording and identity paths. With MEDIUM projection handling, validate each GROUPED contract and batch only accepted ones. Without error settings, keep HARD handling.

Customer results use saved **original records and layout**, not outgoing JSON. Add code/remarks in the input format. Save all findings and their source references separately. See [error handling](error-handling-v2.md).
