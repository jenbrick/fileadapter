# Extension points

The current pipeline always uses configurable projections. Start with the [change guide](developer-walkthrough.md) and [template reference](flexible-mappings.md).

| Need | Extension |
| --- | --- |
| New delimiter/header order/field position | Edit `Profile.Layout` configuration |
| Different source names/nesting | Edit `layout.fields` selectors and aliases |
| New output property, object, collection or root shape | Edit `projection.root` |
| Different filtering/group keys | Edit collection `where` / `group-by` |
| New physical syntax | Implement `FileFormatAdapter`, add format/layout validation |
| New read-only external lookup or multi-field computation | Implement named `RecordEnricher` |
| New scalar conversion | Implement named `ValueTransform` |
| Different HTTP transport/acknowledgement | Implement `UpsertClient` or adjust delivery settings |

## Parser contract

Register the adapter as a Spring `@Component`. Its format must be unique in `AdapterRegistry`. Emit object payloads with increasing logical data record numbers, starting at one. Preserve source values/types until explicit normalization/transforms. Do not perform enrichment or choose output request paths inside the parser. Reject malformed inputs rather than silently dropping records.

## Enrichment contract

`RecordEnricher.name()` supplies the key selected by `projection.enrichers`. `validate(Profile)` checks plugin-specific configuration. `enrich(SourceRecord, Profile, Mode)` returns an object preserving the input record number. Add values under a documented namespace. Keep operations read-only because a crash before persistence can cause lookup repetition.

The example `consent-member` plugin uses `Profile.consent` to configure its member resolver, target lookup hints and date handling. It adds `resolved.owner`, `resolved.manager`, consent GUIDs and a timestamp; it does not construct the HTTP request. To integrate company services, implement `MemberResolver` with those approved contracts rather than changing the projection engine.

## Scalar transform contract

Implement `ValueTransform` with a unique name and register it as a Spring component. `TransformRegistry` discovers extensions. Return the intended Jackson scalar type, and use `transforms: [your-name]` in a source node. Built-ins include trim, case conversion, integer, decimal, boolean, ISO date and US date conversions.

Transformations run before blank handling. Optional blank dates/numbers need an explicitly suitable transform; a failing date conversion does not become null automatically.

## Collection boundaries

The engine supports nested objects/arrays, filtering and grouping within the selected file/record context. It does not execute scripts, arbitrary expressions or joins across unrelated files. FILE/RECORD are the implemented request scopes. Contract-grouped request boundaries, large-volume streaming and chunking would be separate features.

Parser behavior, source normalization, enrichment and projection should each have focused tests. Include input and expected output fixtures when adding a feed or output shape, and run the packaged example verifier when changing their configuration.
