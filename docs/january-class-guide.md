# January class guide

This is the smaller class list for the proposed January release. Start with **CSV, one worker and one complete contract per request**. Include the contract’s members and their provider/payer consent information, with multiple payer consents/coverages where required by the agreed service design.

The first tables link to code that exists today. The later tables list suggested classes for a smaller implementation. **Those suggested classes do not exist yet**; their source column points to POC code we can start from.

We do not need to rename every existing class or rewrite code that already fits. Use the [full POC class list](poc-class-guide.md) for everything else. The [January overview](january-delivery-plan-overview-v2.md), [detailed plan](january-delivery-plan-detailed-v2.md) and [diagrams](january-release-diagrams-v2.md) explain the scope decisions.

## 1. Start the application and receive a finished CSV

| Existing class | What we need it to do for January |
| --- | --- |
| [FileAdapterApplication](../src/main/java/com/jenbrick/fileadapter/FileAdapterApplication.java) | Start the application and support running one file or watching the input folder. Keep the existing resume entry point if restart is included. |
| [IngestionProperties](../src/main/java/com/jenbrick/fileadapter/config/IngestionProperties.java) | Hold folder, mode, polling, request-limit and service settings. |
| [Profile](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Keep the supported CSV layout and feed settings together. It can be simplified if we do not need the POC’s full mapping options. |
| [ProfileCatalog](../src/main/java/com/jenbrick/fileadapter/config/ProfileCatalog.java) | Validate and select the configured feed. Keep selection simple if only one feed is agreed. |
| [LayoutValidator](../src/main/java/com/jenbrick/fileadapter/config/LayoutValidator.java) | Reject invalid CSV settings before processing starts. |
| [RuntimeConfiguration](../src/main/java/com/jenbrick/fileadapter/config/RuntimeConfiguration.java) | Set up Jackson, storage and the single-worker lock. Confirm storage with deployment; H2 on disk may be sufficient for January. |
| [DirectoryPoller](../src/main/java/com/jenbrick/fileadapter/processing/DirectoryPoller.java) | Wait for a finished file and ready marker. Do not read a file while the producer is writing it. |
| [FileProcessor](../src/main/java/com/jenbrick/fileadapter/processing/FileProcessor.java) | Coordinate the run from receipt through preparation, sending and result files. |

## 2. Read records and find the group/member

| Existing class | What we need it to do for January |
| --- | --- |
| [FileFormatAdapter](../src/main/java/com/jenbrick/fileadapter/source/FileFormatAdapter.java) | Keep file reading behind one interface so later formats can be added. |
| [AdapterRegistry](../src/main/java/com/jenbrick/fileadapter/source/AdapterRegistry.java) | Select the CSV reader. The current registry can stay even with one enabled format. |
| [DelimitedFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/DelimitedFileAdapter.java) | Read the agreed CSV layout, including quoted fields and blanks. |
| [TextInputs](../src/main/java/com/jenbrick/fileadapter/source/TextInputs.java) | Read with the agreed encoding and reject bad characters. |
| [SourceRecord](../src/main/java/com/jenbrick/fileadapter/source/SourceRecord.java) | Carry the original record number and fields. Save original values before normalization or enrichment. |
| [SourceNormalizer](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java) | Translate partner field names into the names used inside the application. |
| [RecordFailure](../src/main/java/com/jenbrick/fileadapter/source/RecordFailure.java) | Report an error using useful field/record references without copying raw member values into the message. |
| [GroupEnricher](../src/main/java/com/jenbrick/fileadapter/projection/GroupEnricher.java) | Resolve the input external group ID before member lookup. |
| [GroupResolver](../src/main/java/com/jenbrick/fileadapter/consent/GroupResolver.java) | Keep the dncgrp integration behind an interface. |
| [ConfiguredGroupResolver](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredGroupResolver.java) | Adapt the existing group service’s real request/response. Keep synthetic fixtures for PREVIEW tests. |
| [GroupSettings](../src/main/java/com/jenbrick/fileadapter/consent/GroupSettings.java) | Hold group endpoint/settings and the returned internal group number/customer ID. |
| [GroupContextStore](../src/main/java/com/jenbrick/fileadapter/projection/GroupContextStore.java) | Save successful group context for the run so rows in that group can reuse it. |
| [ConsentMemberEnricher](../src/main/java/com/jenbrick/fileadapter/projection/ConsentMemberEnricher.java) | Build the member-only request with demographics/identifiers and resolved group context. Validate returned identities. |
| [MemberLookupPayload](../src/main/java/com/jenbrick/fileadapter/consent/MemberLookupPayload.java) | Explicitly list permitted member-request fields. Do not send consent preferences, payer/coverage fields or the upsert JSON. |
| [MemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/MemberResolver.java) | Keep the dmqmbr demographics integration behind an interface. |
| [ConfiguredMemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredMemberResolver.java) | Adapt the actual demographics request/response and return internal contract, PIN and member GUID. |
| [ConsentSettings](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | Hold member lookup/date settings and returned identity types. The name does not mean the service should find a particular payer consent. |
| [EnrichedRecordStore](../src/main/java/com/jenbrick/fileadapter/projection/EnrichedRecordStore.java) | Save completed member resolutions where practical, so interrupted preparation can reuse them. |

If either preference requests a change, the group call comes first. If group context is missing or incomplete, do not call the member service. If an existing consent GUID needs its own lookup, agree a separate consent-identity call after resolving the member; that lookup is not implemented just by reusing these classes.

## 3. Prepare, send and write results

| Existing class | What we need it to do for January |
| --- | --- |
| [PreparationCoordinator](../src/main/java/com/jenbrick/fileadapter/results/PreparationCoordinator.java) | Check records, handle agreed error rules and prepare eligible contracts before the first consent POST. Adapt its current dependency on the configurable engine if using smaller builders. |
| [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java) | Reuse its complete-contract gathering/limit logic, or move the relevant logic into the proposed assembler below. Rows for a contract can be scattered through the file. The current class consumes POC projection settings. |
| [RequestValidator](../src/main/java/com/jenbrick/fileadapter/mapping/RequestValidator.java) | Reuse required/type/null checks where helpful. Add checks for the agreed receiving-service schema and limits; this class alone is not full schema validation. |
| [ProcessingLedger](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java) | Save exact request bodies, keys and outcomes. Keep earlier acknowledged work recorded. Use the agreed production storage rather than assuming H2 is mandatory. |
| [RunSnapshot](../src/main/java/com/jenbrick/fileadapter/processing/RunSnapshot.java) | Save the settings used for a run, especially if restart/resume is included. New YAML must not change a saved request being retried. |
| [PreviewWriter](../src/main/java/com/jenbrick/fileadapter/processing/PreviewWriter.java) | Let developers inspect the outgoing JSON without sending consent updates. |
| [UpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/UpsertClient.java) | Keep sending behind an interface so the JSON builder and file reader do not depend on HTTP details. |
| [HttpUpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java) | Send the saved body and interpret the agreed completion/error response. Enable retry only when duplicate handling is agreed. |
| [ErrorPolicy](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | Define HARD stops, the agreed MEDIUM rejection boundary and approved customer wording. SOFT warnings remain optional for January. |
| [ResultStore](../src/main/java/com/jenbrick/fileadapter/results/ResultStore.java) | Save original rows, member/contract references and every error/warning that we implement. |
| [SourceResultWriter](../src/main/java/com/jenbrick/fileadapter/results/SourceResultWriter.java) | Write affected original CSV rows with code/remarks. Enable CSV for January; other output formats can wait. |
| [ResultPublisher](../src/main/java/com/jenbrick/fileadapter/results/ResultPublisher.java) | Write UCD results and the DBS IT report, then mark complete files ready for outside pickup. Do not send customer files or email from here. |

Agree whether MEDIUM rejects a whole contract or only a reliably identified member. The current preparation code rejects contracts; member-level preparation rejection would be additional work. Current service-response handling can identify a failed member under the optional protocol below.

Keep accepted work recorded if a later request fails. If we do not know whether a request succeeded, hold it for investigation instead of returning those members as failed. The amount of automatic restart/reconciliation is still a scope decision; the need to avoid guessing outcomes is not.

## 4. Suggested smaller classes to build or extract

These names match the proposed January diagrams. They describe responsibilities, not a required number of new Java files. Start from the linked code rather than treating every row as a rewrite.

| Suggested class/interface | What it would do | POC starting point |
| --- | --- | --- |
| `PreparationService` | Coordinate validation, group/member lookup, assembly and JSON building. It can be the existing coordinator adapted to the smaller design. | [PreparationCoordinator](../src/main/java/com/jenbrick/fileadapter/results/PreparationCoordinator.java), [SourceNormalizer](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java), [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) |
| `ContractAssembler` | Gather all eligible rows for a contract and its members. Combine identical repeated consent/coverage information and flag conflicts. Retain references to original rows. | [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java) for complete groups/limits; [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) for nested consent/coverage grouping and agreement checks |
| `PayloadBuilder` | Define how prepared contract data becomes the agreed JSON body. | [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) currently does this through configuration |
| `SingleContractPayloadBuilder` | Use Jackson to create one contract object with all eligible member consent information. | [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) and the [single-contract expected bodies](../examples/flexible/expected-contract-single-requests.json) |
| `ContractArrayPayloadBuilder` — optional | Wrap several complete contract objects in one array, up to the agreed limit. Use the same assembly and delivery path. | [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java) and the [array expected bodies](../examples/flexible/expected-contract-array-requests.json) |
| `GroupClient` — optional naming | Adapt dncgrp without exposing its service-specific format to the file reader. We can keep the existing resolver names. | [GroupResolver](../src/main/java/com/jenbrick/fileadapter/consent/GroupResolver.java), [ConfiguredGroupResolver](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredGroupResolver.java) |
| `MemberClient` — optional naming | Adapt dmqmbr using only the member payload and group context. We can keep the existing resolver names. | [MemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/MemberResolver.java), [ConfiguredMemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredMemberResolver.java), [MemberLookupPayload](../src/main/java/com/jenbrick/fileadapter/consent/MemberLookupPayload.java) |
| `ConsentClient` — optional naming | Send prepared consent requests and return agreed outcomes. The existing interface may be enough. | [UpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/UpsertClient.java), [HttpUpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java) |
| `ProcessingStore` — optional interface | Put saved run, lookup, request and result state behind the chosen storage implementation. | [ProcessingLedger](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java), [GroupContextStore](../src/main/java/com/jenbrick/fileadapter/projection/GroupContextStore.java), [EnrichedRecordStore](../src/main/java/com/jenbrick/fileadapter/projection/EnrichedRecordStore.java), [ResultStore](../src/main/java/com/jenbrick/fileadapter/results/ResultStore.java) |
| `ResultWriter` — optional facade | Offer a small entry point for customer/IT files without combining it with actual transmission/email. | [ResultPublisher](../src/main/java/com/jenbrick/fileadapter/results/ResultPublisher.java), [SourceResultWriter](../src/main/java/com/jenbrick/fileadapter/results/SourceResultWriter.java) |

Keep JSON property names/nesting in the builders. Changing the receiving JSON should not require edits to the CSV parser or group/member lookup.

### Suggested internal data types

These are also proposed, with no source files yet. They organize the application’s data; they are not an approved outgoing API schema.

| Suggested type | What it holds | Existing example/reference |
| --- | --- | --- |
| `ContractModel` | Internal contract identity and its members. | [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java) currently groups source records by resolved contract. |
| `MemberModel` | PIN, member GUID and that member’s consents/source references. | [ConsentSettings](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) contains `MemberIdentity`; [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) groups consent entries by member. |
| `ConsentModel` | Agreed consent identity, target, status, dates, manager, transaction details and coverages where applicable. | [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) plus [expected consent JSON](../examples/flexible/expected-envelope.json). A separate consent GUID lookup may still be needed. |
| `CoverageModel` | Payer, policy and policyholder information under the correct payer consent. | [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) and [contract examples](contract-batching-v2.md). |
| `SourceRecordReference` | Run ID and original record number, used to find the original submitted row. | [SourceRecord](../src/main/java/com/jenbrick/fileadapter/source/SourceRecord.java), [ResultStore](../src/main/java/com/jenbrick/fileadapter/results/ResultStore.java) and [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java)’s `Request` record. |

Do not put the actual source rows inside the HTTP JSON just because we keep references internally. Several rows for the same member/contract can contribute to one request.

## 5. Classes we may use, depending on the agreed scope

| Existing class | When it fits |
| --- | --- |
| [ScopedResponseReader](../src/main/java/com/jenbrick/fileadapter/results/ScopedResponseReader.java) | Use/adapt when the receiving service supplies completed member/contract outcomes. Its current `SCOPED_V1` format is only a POC example. |
| [ServiceResultProcessor](../src/main/java/com/jenbrick/fileadapter/results/ServiceResultProcessor.java) | Match a reliably identified member/contract rejection back to that request’s original rows. Member GUID alone cannot identify one particular coverage row. |
| [Projection](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) and [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) | Keep these if reusing the YAML mapping engine helps delivery. They are an alternative to the smaller builders, not an extra engine we must build for January. |
| [RecordEnricher](../src/main/java/com/jenbrick/fileadapter/projection/RecordEnricher.java) | Keep the existing named lookup chain if reusing the current orchestration; a fixed two-call flow does not need a new plugin system. |
| [MappingValues](../src/main/java/com/jenbrick/fileadapter/mapping/MappingValues.java), [TransformRegistry](../src/main/java/com/jenbrick/fileadapter/mapping/TransformRegistry.java), [ValueTransform](../src/main/java/com/jenbrick/fileadapter/mapping/ValueTransform.java) and [JsonPaths](../src/main/java/com/jenbrick/fileadapter/mapping/JsonPaths.java) | Keep the pieces needed by the chosen mapping approach. If using typed builders, do not recreate the full general mapping language. |

The POC already supports arrays, warnings and recovery options. Whether they are all required for January is still being discussed.

## 6. Leave these implementations out of January’s active flow

| Existing class | Why it can wait |
| --- | --- |
| [FixedWidthFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/FixedWidthFileAdapter.java) | January starts with CSV. |
| [JsonFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/JsonFileAdapter.java) | JSON and JSON Lines input can wait for a feed that needs them. |
| [XmlFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/XmlFileAdapter.java) and [XmlNames](../src/main/java/com/jenbrick/fileadapter/config/XmlNames.java) | XML input can wait for a feed that needs it. |

There is no need to delete these from the POC. Keep them as examples while the release enables the agreed CSV feed. Multiple workers, a configuration UI, unlimited-file streaming, generalized scheduling and automatic email/customer delivery are also outside the proposed initial scope.

## 7. Existing tests to start from

| Test class | January checks to keep or adapt |
| --- | --- |
| [AdapterAndMappingTest](../src/test/java/com/jenbrick/fileadapter/AdapterAndMappingTest.java) | CSV parsing, field names, required fields and conversions. |
| [GroupMemberLookupTest](../src/test/java/com/jenbrick/fileadapter/GroupMemberLookupTest.java) | dncgrp before dmqmbr, exact member-only fields, incomplete context, lookup errors and saved results. |
| [RequestBatcherTest](../src/test/java/com/jenbrick/fileadapter/RequestBatcherTest.java) | Scattered rows, complete contracts and member/row limits. |
| [FlexibleProjectionIntegrationTest](../src/test/java/com/jenbrick/fileadapter/FlexibleProjectionIntegrationTest.java) | Reuse its inputs/expected JSON to test the chosen builder or retained mapping engine. |
| [FlexibleProjectionLiveTest](../src/test/java/com/jenbrick/fileadapter/FlexibleProjectionLiveTest.java) | Exact HTTP bodies, acknowledged work and any included retry/restart behavior. |
| [ProcessingResultsTest](../src/test/java/com/jenbrick/fileadapter/ProcessingResultsTest.java) | HARD/MEDIUM behavior, optional warnings and correct original failed rows. |
| [CustomerResultFormatsTest](../src/test/java/com/jenbrick/fileadapter/CustomerResultFormatsTest.java) | Keep CSV return checks; other formats remain POC coverage. |
| [ScopedServiceResultsTest](../src/test/java/com/jenbrick/fileadapter/ScopedServiceResultsTest.java) | Adapt to the actual member/contract response format and partial-success agreement. |

`AdditionalAdaptersTest` covers formats outside January; it remains part of the full POC test suite and is listed in the [full class guide](poc-class-guide.md).

Use representative synthetic records while partner files/services are unavailable. Document supported file/request limits. If arrays, warnings or restart are included, add their acceptance cases explicitly rather than assuming the POC makes them agreed January requirements.
