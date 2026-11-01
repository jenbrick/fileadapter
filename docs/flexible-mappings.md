# Flexible file-to-JSON mappings

This POC separates physical file parsing, optional business enrichment, configurable JSON assembly, and HTTP delivery. Consent is a worked example, not a fixed API contract.

The current working configuration is [application-flexible-demo.yml](../src/main/resources/application-flexible-demo.yml). It reproduces [the sample committed on October 29](../examples/json-sample/CONSENT%20JSON%20ARRAY.txt): an object with a `consents` collection, provider `coverage: []`, payer coverage arrays, and an entry-level `consentManager`.

## Run the example

From the repository root in Git Bash:

```bash
./gradlew test bootJar
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

The profile disables polling, uses synthetic member lookup and PREVIEW, and writes to `var-flexible-demo`. Open the logged run's `flexible-consents.csv.preview.json`. The single request body should equal [expected-envelope.json](../examples/flexible/expected-envelope.json).

Three input rows represent two members. The second member appears twice with different payers. Mapping produces two provider entries and one payer consent containing two coverages. Repeated provider data is coalesced by configured grouping, not emitted once per physical row.

The fixture includes four extra audit columns after the original 30 fields: `PROVIDER_SOURCE_SYSTEM`, `PAYER_SOURCE_SYSTEM`, `SOURCE_DATETIME`, and `SOURCE_USER`. They make the different `MDB`/`XYZ` values and offset-free timestamp explicit input data. These are illustrative additions, not mandatory columns in a universal feed schema. Use selectors, defaults, literals or an enricher for the actual producer's metadata.

## What changes without Java

| Change | Configuration |
| --- | --- |
| Rename or add an output property | Edit an `object` property |
| Move manager to another nesting level | Move its `source: resolved.manager` node |
| Rename the wrapper or return a root array | Change `projection.root` |
| Build an empty object, empty array, null or constant | `literal-json` |
| Rename a source column | `layout.fields` aliases/selectors or a node's `source` |
| Convert a value or translate a code | `transforms`, `values`, `blank-policy` |
| Combine multiple rows per member | Collection `group-by` |
| Collect multiple coverages under one consent | Nested `array` collection |
| Include a collection only for populated preference fields | Collection `where` |
| Request boundaries | `scope: RECORD`, `scope: FILE`, or bounded `scope: GROUPED` with `batching` |

New syntax requires a file adapter. New external lookups or genuinely new computations require a small `RecordEnricher` or `ValueTransform` implementation. Template changes themselves do not require a consent-specific Java builder.

## Template vocabulary

A projection declares a version, scope, a row limit, optional enrichers and a root JSON tree. Each tree node has **exactly one** constructor:

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

`source` uses the existing simple dotted object paths. It can copy a complete object or array, including an enriched identity. Nested array construction uses collections rather than expressions embedded in property names.

### Scalar values

Scalar source nodes use these conversion rules:

- `required` rejects missing, null or blank values.
- `default-value` is a string applied to missing fields only.
- `default-json` is a typed fallback for an optional source omitted by mapping (missing or blank with OMIT). It is applied after scalar mapping, preserves explicit null, and cannot be combined with `required` or `default-value`. The example uses `'null'` for absent consent GUIDs.
- Named `transforms` run in order, before blank handling.
- `blank-policy` is OMIT, NULL, EMPTY or REJECT. OMIT is the default.
- `values` explicitly translates values; unknown codes fail.
- Explicit optional null is preserved and bypasses transformations.

A blank date/numeric source fails its conversion before blank handling. Use a suitable custom transform when optional blanks need a different conversion policy. `literal-json` supplies typed constants such as `'[]'`, `'{}'`, `'null'`, `'123'`, or `'"MDB"'`. Literals must contain exactly one valid JSON value and cannot contain duplicate object keys.

An optional missing source property is omitted. A source node may copy `resolved.owner` as an object, so changing its output location is just a tree edit.

### Object and array checks

`checks` on a template node accepts the existing contract rules: dotted path, JSON type, required and nullable, relative to the constructed node. `input-contract` validates normalized source fields before enrichment. These are explicit path/type checks, not a full JSON Schema implementation.

For example:

```yaml
input-contract:
  - { path: PROVIDER_CLINICAL_CONSENT_PREFERENCE, type: STRING, required: true, nullable: true }
```

### Filtering and grouping

An array can contain several collection rules. Rules append their results in configuration order, and each rule preserves first-seen group order. The working example builds all provider groups first, then payer groups, matching the supplied sample.

`where` supports either:

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

Present means nonmissing, nonnull and nonblank for strings, or nonempty for containers. Equality is exact; apply source normalization when producer codes differ. `any-present` includes partial coverage rows so required item mappings/group keys can reject them rather than silently dropping them.

Without `group-by`, each selected row produces one item. With it, rows sharing the configured nonblank scalar keys produce one item. Every mapped scalar in that group must agree **after transformation**. Conflicting status, date, manager or other mapped values fail preflight. No first-row or last-row winner is selected.

Nested collections see only the parent group's rows. The example groups payer consents by resolved member GUID and coverage items by payer ID plus policy ID. Identical duplicate coverage rows coalesce. Conflicting mapped values for the same coverage key fail. These are sample grouping keys; change them when the real business keys are known. Group keys use source values as supplied, so normalize padding/case consistently when they are significant.

## Demonstrate a changing JSON contract

Use the same CSV with a root array:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-root-array-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

That profile points the root at the existing consent-array template. Compare with [expected-root-array.json](../examples/flexible/expected-root-array.json).

Use the same CSV with renamed nesting and moved fields:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-reshaped-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

This changes only YAML:

- `consents` moves to `payload.entries`.
- `consentManager` moves inside `transactionDetails`.
- `coverage` becomes `payerInfo.coverages`.

Compare with [expected-reshaped.json](../examples/flexible/expected-reshaped.json). This alternate shape demonstrates flexibility; it does not claim the endpoint accepts that contract.

For a domain-independent per-record example:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=flexible-record-v1 \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

It has no enrichers and maps names into `person.firstName` and `person.lastName`, producing three requests.

## Demonstrate different file adapters

The [format guide](formats.md) lists all 11 input layouts, their profile IDs and expected output bodies. Run every input plus the output variants:

```bash
bash scripts/run-examples.sh
```

For semantic comparison with expected JSON, after building the JAR:

```bash
python scripts/verify-examples.py
```

CSV/pipe with and without headers, semicolon, tab, fixed width, XML, JSON array, JSON object and JSON Lines have runnable fixtures. The single JSON object represents one member; the other formats contain the equivalent three-row dataset. Fixed width uses 32-character fields, XML selects `/members/member`, and input JSON mode is independent of output root shape.

## Enrichment extension

`RecordEnricher` is a named Spring extension:

```java
public interface RecordEnricher {
    String name();
    SourceRecord enrich(SourceRecord source, Profile profile, Mode mode);
}
```

Configure `enrichers: [consent-member]` for the included example. That plugin reuses the existing `MemberResolver`, reads its lookup settings from `profile.consent`, and adds `resolved.owner`, `resolved.manager` and existing consent GUIDs. The `resolved` namespace is reserved by this plugin. The projection decides where those values appear in the output. Output shape is determined entirely by the template.

`consent-member` uses the canonical uppercase field names shown in the example. Use `layout.fields` to normalize different physical source names before enrichment. Another domain can register a different enricher and omit `profile.consent` entirely. Enrichers must preserve row number, return an object, and keep external operations read-only.

The fixture mode is explicitly a demo. LIVE refuses fixture member lookup. Set `FLEXIBLE_LOOKUP_MODE=HTTP` and `CONSENT_LOOKUP_URL` to exercise the proposed facade, with `CONSENT_LOOKUP_BEARER_TOKEN` for authentication. That facade is still a proposed integration contract, not the actual company API. `UPSERT_URL` and `UPSERT_BEARER_TOKEN` control delivery independently. The HTTP integration tests exercise the facade and delivery independently.

## Scope, persistence and limits

- `FILE` groups the selected file into one request. `RECORD` projects each row independently and cannot combine coverages across rows.
- The original example chooses FILE to demonstrate a multi-member envelope. The new contract profiles use GROUPED to bound requests, with one contract object or an array of contract objects. Transaction guarantees remain endpoint decisions.
- `skip-when-empty` is a dotted result path, or `$` for the root. If that value is missing/null or an empty container, no request is staged. Set it to `consents` for the supplied envelope.
- Enriched rows are persisted in H2 before projection. Interrupted STAGING reuses saved lookup results; a crash before saving may repeat a read-only lookup.
- The complete mapped request is staged before delivery. READY/resume sends the saved JSON without new lookup or mapping. Full-file validation still precedes any upsert.
- A grouped request retains all contributing source row numbers. Named previews and delivery errors report `sourceRecordNumbers` for aggregates and the singular field for a single-row request.
- Projection configuration is in the immutable snapshot. Projection snapshots use engine version 4. Earlier flexible snapshots remain readable; engine versions 1–3 require the previous release.
- The projection POC materializes a bounded file in memory. Set `max-records` from 1 to 10,000; the supplied examples use 1,000. Exceeding it fails before delivery. This is not an unbounded streaming aggregation engine.
- Template trees allow at most 32 nesting levels and 1,024 configured nodes. There are no scripts, joins across unrelated sources, arbitrary expressions or distributed transactions.

Record projections currently share this bounded-file preflight implementation. Keep large-volume streaming/chunking as a separate extension rather than hiding it in adapters. The service must still guarantee idempotency before replay is enabled, and a successful HTTP status must acknowledge the complete selected request body.

## Working on a real mapping

Copy the full configuration to an external YAML file and supply it with `--spring.config.additional-location=file:./config/feed.yml`. Spring replaces profile lists, so include the complete list required for that run. Edit the YAML and restart; no mapping hot reload is provided. Rebuild only when changing packaged resources or Java code.

Version meaningful rule changes. Update the corresponding input/expected-output fixtures, run preview, and inspect the diff before pointing LIVE at a real service. The [developer walkthrough](developer-walkthrough.md) connects the configuration to the code.

## Validation

The retained tests exercise parsers, scalar conversions, required/type/nullability checks, exact sample output, alternate JSON shapes, grouping conflicts, enrichment snapshots and HTTP delivery/retry. The packaged verifier checks each supported input layout and output variant against expected JSON. See [verification notes](verification.md) for the checks performed for this cleanup.

## GROUPED scope: bounded contract request bodies

See [contract batching](contract-batching.md) for complete commands, expected JSON and the distinction between consent identities and payer coverages. Both new demo profiles are in the same configuration file as the original examples.

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

`batching` is valid only with GROUPED. It requires a nonempty list of scalar grouping paths, a group/request cap from 1 to 10000 and a row/group cap from 1 through `max-records`. `member-key` and `max-members-per-group` are optional but must appear together; the member cap must be between 1 and the row/group cap. `where` uses the same condition syntax as collection filters. Filtered no-change rows still count against the file record cap.

Rows are gathered by first-seen group key, including nonadjacent rows. A complete group is indivisible. The batcher rejects oversized groups before rendering, then emits at most the configured number of groups per request and flushes the last partial batch. Rendering, scalar agreement checks, `skip-when-empty`, provenance and node validation apply independently to each partition. A rendering error in a later partition still blocks delivery of all partitions during file preparation.

The single-contract root reads `resolved.owner.contract` as `contractId` and renders consent entries for that contract. The array root uses a collection grouped by that same contract path and places the single-contract object inside it. The root’s nested grouping remains necessary when more than one contract is selected; scalar contract IDs cannot agree across distinct contracts.

The new payer collection additionally groups by `PAYER_CONSENT_KEY` within each member. The enricher forwards this optional source field to lookup; an HTTP facade must resolve the corresponding existing payer-consent GUID. The field is required by the new payer grouping, not by the legacy one-payer-consent-per-member profiles. It is not emitted automatically.

`CONTRACTS_PER_REQUEST` (array demo only), `CONTRACT_MAX_ROWS` and `CONTRACT_MAX_MEMBERS` are environment-backed profile settings. These limits bound request composition, not JSON byte size. File preparation remains bounded and in memory; arbitrary-file streaming is not implemented.

## Error policy and result format are independent of projection shape

The profile’s `errors` settings control literal validation rules, HARD/MEDIUM/SOFT behavior, approved customer text and source identity paths. See [error/result handling](error-handling.md). MEDIUM projection handling prevalidates each GROUPED contract and then batches viable contracts; without an error policy, prior HARD behavior is preserved. The customer return writer uses saved **original source** records and layout, not the projected JSON, and appends code/remarks in that incoming format. The result store retains all findings and service-to-source correlation independently of output nesting.
