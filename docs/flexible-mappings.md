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
| One request per row versus an aggregate request | `scope: RECORD` or `scope: FILE` |

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
- The example chooses FILE to demonstrate a multi-member envelope. Batch size and transaction guarantees remain endpoint decisions.
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
