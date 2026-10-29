# Extending FileAdapter

## Current projection and enrichment extensions

The [flexible mapping guide](flexible-mappings.md) is the current mapping extension reference. Configure `projection.root` to change JSON objects, nested arrays, wrappers, source selectors, grouping and defaults. Register a named `RecordEnricher` for another business lookup or calculation; enrichment adds values, while the template selects every output property. The included consent-member plugin uses MemberResolver and does not build the output contract.

Projection profiles bypass the older specialized ConsentRequestPlanner and generic object-path mapper. The engine's collection templates now implement bounded grouping and nested array construction. References below to unsupported array expressions describe the older scalar-path mapper, not the projection strategy.

## Consent enrichment and array assembly

The implemented consent path uses `ConsentRequestPlanner`, `MemberResolver` and `ConsentPlanStore` instead of generic one-record/one-request mapping. Replace the resolver to compose real company APIs/queries. Change `Profile.consent` for preference/relationship maps, targets, date pattern, source metadata and lookup settings. Change the planner when the approved API adds new structures. See [the consent guide](consent.md) for the proposed facade contract, unresolved assumptions and durable plan recovery. Preserve old snapshot behavior when changing business rules.

## Add a file format

Delimited, fixed-width, XML, and JSON adapters are already implemented; configure them using [the format guide](formats.md). For an additional syntax, add its identifier to `Profile.Format` and validation to `LayoutValidator`, then implement `FileFormatAdapter` as a Spring component. For example, after adding `YAML`:

```java
@Component
public class YamlFileAdapter implements FileFormatAdapter {
    public Profile.Format format() { return Profile.Format.YAML; }

    public void read(Path file, Profile.Layout layout,
                     Consumer<SourceRecord> consumer) throws IOException {
        // Open and close parser resources here.
        // Emit one record per agreed business record boundary.
        // Preserve nested objects, arrays, and explicit nulls in JsonNode.
    }
}
```

The registry discovers beans automatically and rejects duplicate registrations. Add format-specific settings to `Profile.Layout` (or introduce format-specific layout records) once the actual feed is known. A single universal layout with dozens of speculative fields is deliberately avoided.

Record numbers must be positive, unique, increasing within a file, and stable across repeated parses of the same input. They identify source rows. Sequential request numbers identify ledger delivery entries and idempotency keys, allowing each populated consent row to produce one array request with up to two entries. Keep parser resource ownership inside `read`; do not expose a lazy stream after closing its file reader.

The existing fixed-width adapter counts Unicode code points, with zero-based offsets. XML uses namespace-aware absolute record paths and rejects DTDs/entities and mixed content. JSON uses an explicit OBJECT, ARRAY, or LINES mode. Headerless delimited files use an explicit ordered `columns` list. These choices cannot be inferred reliably from a file extension.

## Add a business transformation

```java
@Component
public class NormalizePlanCode implements ValueTransform {
    public String name() { return "normalize-plan-code"; }

    public JsonNode apply(JsonNode value) {
        return TextNode.valueOf(value.asText().trim().toUpperCase(Locale.ROOT));
    }
}
```

Reference it as `transforms: [normalize-plan-code]`. Names must be unique; unknown names fail configuration validation. Keep transforms deterministic, independently tested, and compatible with snapshots still awaiting preflight. Avoid remote lookups inside a field transform; use an explicit staging/enrichment step with a defined failure and caching policy.

## Replace mapping or validation

`RequestMapper` is an interface. Supply an `@Primary` implementation to replace the configurable mapper with complex Java rules or typed DTO assembly converted to an `ObjectNode`. The delivery layer accepts `JsonNode`, so source parsing and HTTP transport remain independent. `RequestValidator` can likewise be replaced with an `@Primary` subclass or refactored to a dedicated interface when adding a JSON Schema validator.

The mapping engine supports nested object paths such as `person.contact.email`; array-index expressions, conditionals, joins, and arbitrary expression evaluation are intentionally absent. Custom transforms can return JSON objects/arrays. For many source rows per target entity, introduce a per-run assembler **between normalization and mapping** and define aggregation keys, ordering, duplicate/conflict rules, bounded staging, and stable output identifiers. Do not hide grouping inside the CSV syntax parser.

## Add an operator reconciliation workflow

For a non-idempotent service, a timeout cannot establish whether a POST committed. Extend `UpsertClient` with a service-supported status lookup, and add an audited operation that marks a request acknowledged or explicitly safe to retry after reconciliation. Do not convert `SENDING`/`UNCERTAIN` into `PENDING` automatically.

## Evolve output contracts

For generic profiles, change mapping definitions and contract rules together. For consent profiles, change consent rules or the planner and its fixtures together. Run representative input/output fixtures in preview. Keep the current request endpoint and authentication outside input adapters. Version profile IDs when meaning changes. Existing runs retain their original profile and delivery snapshot, so newly edited settings cannot silently alter partially delivered requests.

## Preserve old processing snapshots

New optional layout properties are omitted from JSON when absent, preserving old CSV profile serialization and content-based run identity. Old snapshots deserialize with null format-specific options. Changes to existing parser/mapping semantics still require explicit version planning; adding optional properties alone must not silently change old run identities.
