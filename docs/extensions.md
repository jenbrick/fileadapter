# Extending FileAdapter

## Add a file format

Implement `FileFormatAdapter` as a Spring component:

```java
@Component
public class XmlFileAdapter implements FileFormatAdapter {
    public Profile.Format format() { return Profile.Format.XML; }

    public void read(Path file, Profile.Layout layout,
                     Consumer<SourceRecord> consumer) throws IOException {
        // Open and close parser resources here.
        // Emit one record per agreed business record boundary.
        // Preserve nested objects, arrays, and explicit nulls in JsonNode.
        // XML: disable DTDs/external entities; define namespaces and record paths.
    }
}
```

The registry discovers beans automatically and rejects duplicate registrations. Add format-specific settings to `Profile.Layout` (or introduce format-specific layout records) once the actual feed is known. A single universal layout with dozens of speculative fields is deliberately avoided.

Record numbers must be positive, unique, increasing within a file, and stable across repeated parses of the same input. They identify ledger entries and idempotency keys. Keep parser resource ownership inside `read`; do not expose a lazy stream after closing its file reader.

Fixed-width feeds need offsets/lengths and an explicit byte-versus-character convention. XML needs record boundaries, namespaces, and cardinality rules. JSON needs root-object versus array versus JSON Lines semantics. Headerless delimited files need an explicit ordered `columns` list. These choices cannot be inferred reliably from an extension.

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

The baseline supports nested object paths such as `person.contact.email`; array-index expressions, conditionals, joins, and arbitrary expression evaluation are intentionally absent. Custom transforms can return JSON objects/arrays. For many source rows per target entity, introduce a per-run assembler **between normalization and mapping** and define aggregation keys, ordering, duplicate/conflict rules, bounded staging, and stable output identifiers. Do not hide grouping inside the CSV syntax parser.

## Add an operator reconciliation workflow

For a non-idempotent service, a timeout cannot establish whether a POST committed. Extend `UpsertClient` with a service-supported status lookup, and add an audited operation that marks a request acknowledged or explicitly safe to retry after reconciliation. Do not convert `SENDING`/`UNCERTAIN` into `PENDING` automatically.

## Evolve output contracts

Change mapping definitions and contract rules together, then run representative input/output fixtures in preview. Keep the current request endpoint and authentication outside input adapters. Version profile IDs when meaning changes. Existing runs retain their original profile and delivery snapshot, so newly edited settings cannot silently alter partially delivered requests.
