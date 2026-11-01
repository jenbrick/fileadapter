# Where to change or extend the code

The POC builds JSON from configured projections. Start with the [developer change guide](developer-walkthrough-v2.md) and [mapping reference](flexible-mappings-v2.md).

| What needs to change | Where to change it |
| --- | --- |
| Delimiter, header order or field positions | `Profile.Layout` settings |
| Input field names or nesting | `layout.fields` selectors and aliases |
| Outgoing field, object, collection or overall JSON structure | `projection.root` |
| One or several complete contracts per request | `scope: GROUPED` and `projection.batching`; see [contract batching](contract-batching-v2.md) |
| Which rows are included or grouped together | Collection `where` / `group-by` settings |
| A new kind of file syntax | Implement `FileFormatAdapter` and validate its format/layout settings |
| Another read-only service lookup or calculation using several fields | Implement a named `RecordEnricher` |
| A new conversion for one field | Implement a named `ValueTransform` |
| Customer codes/remarks, severity or contract rejection rules | Profile `errors`; see [error handling](error-handling-v2.md) |
| How to write a customer return for a new file syntax | Extend `SourceResultWriter` along with the parser |
| How requests are sent or acknowledged | `UpsertClient` or delivery settings |

## Adding a file reader

Register the adapter as a Spring `@Component`. Its format must be unique in `AdapterRegistry`. Return object records numbered from 1, increasing for each logical data record. Preserve source values and types until a configured normalization or conversion changes them.

Keep service lookups and outgoing JSON decisions out of the parser. Reject malformed input rather than quietly dropping records.

## Adding a lookup or enrichment step

`RecordEnricher.name()` gives the name selected in `projection.enrichers`. `validate(Profile)` checks its settings. `enrich(SourceRecord, Profile, Mode)` returns an object with the same record number. Override `enrichForRun(runId, ...)` when the step needs saved run-specific information; otherwise it calls `enrich`.

Put added fields under a documented namespace. Keep these operations read-only: a crash before saving the result can cause the lookup to repeat.

The consent chain is `group-context`, then `consent-member`. `GroupEnricher` uses `Profile.group` and `GroupResolver` to find and save the internal group number/customer ID under `resolvedGroup`. `ConsentMemberEnricher` uses `Profile.consent` and `MemberResolver` to send member demographics/identifiers with that context. It adds owner/manager identities, optional legacy consent GUIDs and a timestamp. The mapping builds the consent JSON afterward.

Adapt the company group API behind `GroupResolver` and the demographics API behind `MemberResolver`. If an existing consent GUID needs its own lookup, add that separately after member resolution. Do not put payer-consent matching into the demographics request. See [lookup contracts](group-member-lookups-v2.md).

## Adding a field conversion

Implement `ValueTransform`, give it a unique name and register it as a Spring component. `TransformRegistry` finds it. Return the intended Jackson scalar type and reference it through `transforms: [your-name]`.

Built-ins trim text, change case, convert integers/decimals/booleans and convert ISO/US dates. Conversions happen before blank handling. An optional blank date or number needs a conversion that handles it; a failed date conversion does not automatically become null.

## What grouping supports

The engine can build nested objects/arrays and filter or group records within its current context. It does not run scripts, arbitrary expressions or joins between unrelated files.

`FILE`, `RECORD` and `GROUPED` decide how records become requests. `GROUPED` uses `RequestBatcher` to gather complete groups, enforce row/member limits and put a maximum number into each request. A contract can contain multiple member consents, distinct payer consents and their coverages. Unlimited-size streaming and JSON byte-size checks would be additional work.

Test parsing, normalization, lookup and JSON building separately where appropriate. Add input/expected-output examples for a feed or JSON change, and run the packaged verifier after changing those examples or their settings.

## Changing results or service-response handling

`ResultStore` keeps original records and findings without duplicating them. `PreparationCoordinator` applies rules and rejects contracts before final batching. `SourceResultWriter` adds code/remarks in the original format. `ResultPublisher` writes result revisions and ready manifests. None of these sends customer messages or email.

`HttpUpsertClient` can use the optional `ScopedResponseReader` POC format. `ServiceResultProcessor` translates approved service codes and matches member/contract identities to records in that submitted request.

Put the eventual endpoint’s response handling here. Do not assume an array position identifies an original record. Keep saving responses/results before writing files, avoid duplicate findings during recovery, and pause when a response is incomplete or cannot be matched reliably.
