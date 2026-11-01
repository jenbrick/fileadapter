# Extension points

The current pipeline always uses configurable projections. Start with the [change guide](developer-walkthrough.md) and [template reference](flexible-mappings.md).

| Need | Extension |
| --- | --- |
| New delimiter/header order/field position | Edit `Profile.Layout` configuration |
| Different source names/nesting | Edit `layout.fields` selectors and aliases |
| New output property, object, collection or root shape | Edit `projection.root` |
| One/many complete contracts per request | Use `scope: GROUPED` and `projection.batching`; see [contract batching](contract-batching.md) |
| Different filtering/group keys | Edit collection `where` / `group-by` |
| New physical syntax | Implement `FileFormatAdapter`, add format/layout validation |
| New read-only external lookup or multi-field computation | Implement named `RecordEnricher` |
| New scalar conversion | Implement named `ValueTransform` |
| Customer codes, remarks, severity rules or contract isolation | Configure profile `errors`; see [result handling](error-handling.md) |
| Different incoming-format customer return syntax | Extend `SourceResultWriter` alongside the parser |
| Different HTTP transport/acknowledgement | Implement `UpsertClient` or adjust delivery settings |

## Parser contract

Register the adapter as a Spring `@Component`. Its format must be unique in `AdapterRegistry`. Emit object payloads with increasing logical data record numbers, starting at one. Preserve source values/types until explicit normalization/transforms. Do not perform enrichment or choose output request paths inside the parser. Reject malformed inputs rather than silently dropping records.

## Enrichment contract

`RecordEnricher.name()` supplies the key selected by `projection.enrichers`. `validate(Profile)` checks plugin-specific configuration. `enrich(SourceRecord, Profile, Mode)` returns an object preserving the input record number. An optional `enrichForRun(runId, ...)` override adds run-aware durable context; the default delegates to enrich. Add values under a documented namespace. Keep operations read-only because a crash before persistence can cause lookup repetition.

The example chain is `group-context` then `consent-member`. GroupEnricher uses Profile.group and GroupResolver to obtain/freeze internal group number and customer ID under resolvedGroup. ConsentMemberEnricher uses Profile.consent and MemberResolver for a typed member-only demographic/identifier payload with that context. It adds resolved owner/manager identities, nullable legacy consent GUIDs and a timestamp; templates construct the upsert JSON. Implement the existing group service contract behind GroupResolver and the member demographics API behind MemberResolver. Consent identity lookup, if needed, is a separate extension, not part of the demographic payload. See [the contracts](group-member-lookups.md).

## Scalar transform contract

Implement `ValueTransform` with a unique name and register it as a Spring component. `TransformRegistry` discovers extensions. Return the intended Jackson scalar type, and use `transforms: [your-name]` in a source node. Built-ins include trim, case conversion, integer, decimal, boolean, ISO date and US date conversions.

Transformations run before blank handling. Optional blank dates/numbers need an explicitly suitable transform; a failing date conversion does not become null automatically.

## Collection boundaries

The engine supports nested objects/arrays, filtering and grouping within the selected file/record context. It does not execute scripts, arbitrary expressions or joins across unrelated files. FILE, RECORD and GROUPED are implemented request scopes. GROUPED uses `RequestBatcher` to gather complete groups, enforce row/member caps and pack a bounded number per request. A contract can include multiple member consents, separate payer-consent groups and nested coverage collections. Large-volume streaming and JSON byte-size limits remain separate features.

Parser behavior, source normalization, enrichment and projection should each have focused tests. Include input and expected output fixtures when adding a feed or output shape, and run the packaged example verifier when changing their configuration.

## Result and response extension points

`ResultStore` preserves original records and deduplicated diagnostic events. `PreparationCoordinator` runs configured rules and contract isolation before final request batching. `SourceResultWriter` serializes source-format records with code/remarks; `ResultPublisher` writes revision files and handoff manifests. Neither sends email or customer messages.

`HttpUpsertClient` can opt into the bounded `ScopedResponseReader` POC protocol. `ServiceResultProcessor` translates allowlisted codes and correlates contract/member identities only within a submitted request. Implement the eventual endpoint’s response adapter here rather than assuming array positions identify original rows. Preserve durable response/outcome storage before generating artifacts, idempotent event replay, and safe handling of incomplete/unknown results.
