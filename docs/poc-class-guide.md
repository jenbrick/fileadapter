# POC class guide

Here is the class list for the current POC. Click a name to open its Java source. It covers all **51 production Java files** and **nine test files** in this revision. Records and interfaces are included because they carry data or define the calls between the classes.

For the smaller January plan, use the [January class guide](january-class-guide.md). For the call flow, use the [runtime diagrams](runtime-diagrams-v2.md) or [CSV walkthrough](csv-debug-walkthrough-v2.md).

The main flow is: read a finished file → save original rows → normalize/check them → look up group then member → gather contracts/build JSON → save each request → preview or send → write customer/IT results.

## Startup and settings

| Class or type | Kind | What it does |
| --- | --- | --- |
| [FileAdapterApplication](../src/main/java/com/jenbrick/fileadapter/FileAdapterApplication.java) | class | Starts Spring, then processes one file, resumes a saved run or leaves the folder watcher running. |
| [IngestionProperties](../src/main/java/com/jenbrick/fileadapter/config/IngestionProperties.java) | record | Holds the YAML settings for folders, mode, polling, feed profiles and delivery. |
| [Profile](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | record | Keeps one feed’s reading, lookup, JSON-building and error instructions together. |
| [ProfileCatalog](../src/main/java/com/jenbrick/fileadapter/config/ProfileCatalog.java) | class | Checks feed definitions and chooses exactly one by filename or explicit profile ID. |
| [LayoutValidator](../src/main/java/com/jenbrick/fileadapter/config/LayoutValidator.java) | class | Checks file-layout settings at startup so a bad layout fails before we read a file. |
| [RuntimeConfiguration](../src/main/java/com/jenbrick/fileadapter/config/RuntimeConfiguration.java) | class | Sets up Jackson, H2 on disk and the lock that prevents two processes using the same work folder. |
| [XmlNames](../src/main/java/com/jenbrick/fileadapter/config/XmlNames.java) | class | Resolves XML element paths and namespace names consistently even when prefixes differ. |

## Read and normalize input

| Class or type | Kind | What it does |
| --- | --- | --- |
| [FileFormatAdapter](../src/main/java/com/jenbrick/fileadapter/source/FileFormatAdapter.java) | interface | Defines the common file-reader interface. Readers do not call lookup services or build consent requests. |
| [AdapterRegistry](../src/main/java/com/jenbrick/fileadapter/source/AdapterRegistry.java) | class | Finds the reader registered for the configured input format and rejects duplicate registrations. |
| [DelimitedFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/DelimitedFileAdapter.java) | class | Reads CSV, pipe, semicolon or tab data using a configured delimiter/header layout and emits named source records. |
| [FixedWidthFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/FixedWidthFileAdapter.java) | class | Reads fields by position and keeps the complete original line, including gaps/padding, for customer returns. |
| [JsonFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/JsonFileAdapter.java) | class | Reads a JSON array, a single object or JSON Lines into source records. Input packaging is separate from output JSON. |
| [XmlFileAdapter](../src/main/java/com/jenbrick/fileadapter/source/XmlFileAdapter.java) | class | Reads selected XML records while preserving attributes, repeated children and explicit nulls. |
| [TextInputs](../src/main/java/com/jenbrick/fileadapter/source/TextInputs.java) | class | Opens text with the configured encoding, handles a BOM and rejects malformed characters or oversized lines. |
| [SourceRecord](../src/main/java/com/jenbrick/fileadapter/source/SourceRecord.java) | record | Carries a logical record number and parsed fields, plus original raw text for fixed-width records. |
| [SourceNormalizer](../src/main/java/com/jenbrick/fileadapter/source/SourceNormalizer.java) | class | Translates producer field names or nested selectors into the consistent field names used by lookup/mapping. |
| [RecordFailure](../src/main/java/com/jenbrick/fileadapter/source/RecordFailure.java) | class | Carries processing errors using field names/record references rather than raw member values. |

## Look up group and member information

| Class or type | Kind | What it does |
| --- | --- | --- |
| [GroupResolver](../src/main/java/com/jenbrick/fileadapter/consent/GroupResolver.java) | interface | Defines the group-service call: external group ID in, internal group number and customer ID out. |
| [ConfiguredGroupResolver](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredGroupResolver.java) | class | Uses synthetic group fixtures or calls the configured HTTP group service with only the external group ID. |
| [GroupSettings](../src/main/java/com/jenbrick/fileadapter/consent/GroupSettings.java) | record | Holds group-lookup settings, fixtures and the two required group-context values. |
| [GroupEnricher](../src/main/java/com/jenbrick/fileadapter/projection/GroupEnricher.java) | class | Resolves external group ID first and adds the internal group number/customer ID needed by member lookup. |
| [GroupContextStore](../src/main/java/com/jenbrick/fileadapter/projection/GroupContextStore.java) | class | Saves one successful result per run/external group ID so that group’s rows share the same context. |
| [MemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/MemberResolver.java) | interface | Defines the member-demographics call and its normalized identity result. |
| [ConfiguredMemberResolver](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredMemberResolver.java) | class | Uses synthetic member fixtures or calls the configured HTTP demographics service with the member-only payload. |
| [ConsentSettings](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | record | Holds member-lookup/date settings and the normalized identity result. Despite the name, it does not build consent JSON. |
| [MemberLookupPayload](../src/main/java/com/jenbrick/fileadapter/consent/MemberLookupPayload.java) | record | Lists the nine allowed properties for the member request, including resolved group context. Keeps consent/payer fields out. |
| [ConsentMemberEnricher](../src/main/java/com/jenbrick/fileadapter/projection/ConsentMemberEnricher.java) | class | Uses group context to build the member-only request, validates returned identities and adds them to the working record. |
| [RecordEnricher](../src/main/java/com/jenbrick/fileadapter/projection/RecordEnricher.java) | interface | Defines a named step that adds information to a source record without choosing the final JSON shape. Service operations must be read-only. |
| [EnrichedRecordStore](../src/main/java/com/jenbrick/fileadapter/projection/EnrichedRecordStore.java) | class | Saves completed enriched rows so interrupted preparation can reuse successful lookup work. |

## Prepare data and build JSON

| Class or type | Kind | What it does |
| --- | --- | --- |
| [PreparationCoordinator](../src/main/java/com/jenbrick/fileadapter/results/PreparationCoordinator.java) | class | Normalizes and checks rows, runs lookups, sets aside rejected contracts when allowed and builds requests for the remaining work. |
| [Projection](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) | record | Holds the configured instructions for outgoing JSON: nodes, collections, filters, grouping and request limits. |
| [ProjectionEngine](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) | class | Runs configured enrichment and builds JSON objects/arrays from templates. Uses the batcher when requests contain complete groups. |
| [RequestBatcher](../src/main/java/com/jenbrick/fileadapter/projection/RequestBatcher.java) | class | Gathers scattered rows for each group/contract, checks row/member limits and packs complete groups into requests without splitting them. |
| [JsonPaths](../src/main/java/com/jenbrick/fileadapter/mapping/JsonPaths.java) | class | Reads simple dotted paths such as resolved.owner.contract and checks that configured paths are valid. |
| [MappingValues](../src/main/java/com/jenbrick/fileadapter/mapping/MappingValues.java) | class | Reads one mapped value and applies configured defaults, conversions, blank handling and code translations. |
| [TransformRegistry](../src/main/java/com/jenbrick/fileadapter/mapping/TransformRegistry.java) | class | Finds named conversions such as trim, uppercase and date/number conversion, including registered extensions. |
| [ValueTransform](../src/main/java/com/jenbrick/fileadapter/mapping/ValueTransform.java) | interface | Defines a named conversion for a single value when built-in conversions are not enough. |
| [RequestValidator](../src/main/java/com/jenbrick/fileadapter/mapping/RequestValidator.java) | class | Checks configured required fields, JSON types and null rules. It is not a full API-schema validator. |

## Coordinate, save and send work

| Class or type | Kind | What it does |
| --- | --- | --- |
| [DirectoryPoller](../src/main/java/com/jenbrick/fileadapter/processing/DirectoryPoller.java) | class | Looks for finished files with ready markers and checks unfinished runs before taking more input. |
| [FileProcessor](../src/main/java/com/jenbrick/fileadapter/processing/FileProcessor.java) | class | Coordinates intake, preparation, preview/delivery, results and archiving. Uses saved status to decide where a run continues. |
| [ProcessingLedger](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java) | class | Saves run/request status, bodies, attempts and delivery results in H2 so acknowledged work can be skipped. |
| [RunSnapshot](../src/main/java/com/jenbrick/fileadapter/processing/RunSnapshot.java) | record | Saves the selected profile, mode and delivery settings. Later configuration edits do not change an existing run. |
| [PreviewWriter](../src/main/java/com/jenbrick/fileadapter/processing/PreviewWriter.java) | class | Writes readable previews and exact prospective request bodies without sending consent upserts. |
| [UpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/UpsertClient.java) | interface | Defines the delivery interface and result so orchestration does not depend on one HTTP implementation. |
| [HttpUpsertClient](../src/main/java/com/jenbrick/fileadapter/delivery/HttpUpsertClient.java) | class | Sends saved JSON over HTTP and classifies the response as acknowledged, retryable, rejected or uncertain. Can use the optional scoped-result protocol. |

## Errors and result files

| Class or type | Kind | What it does |
| --- | --- | --- |
| [ErrorPolicy](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | record | Holds HARD/MEDIUM/SOFT rules, contract/member identity paths and approved customer codes/remarks. |
| [ResultStore](../src/main/java/com/jenbrick/fileadapter/results/ResultStore.java) | class | Saves original records, their resolved identities and every finding so results can be recreated without rereading changed input. |
| [ScopedResponseReader](../src/main/java/com/jenbrick/fileadapter/results/ScopedResponseReader.java) | class | Reads the optional POC member/contract-result response, checks completeness and limits its size. This is not the actual service’s agreed contract yet. |
| [ServiceResultProcessor](../src/main/java/com/jenbrick/fileadapter/results/ServiceResultProcessor.java) | class | Matches returned contract/member issues to original rows in that particular request and translates approved service codes. |
| [SourceResultWriter](../src/main/java/com/jenbrick/fileadapter/results/SourceResultWriter.java) | class | Writes original affected records with code/remarks in the incoming format, independently of outgoing consent JSON. |
| [ResultPublisher](../src/main/java/com/jenbrick/fileadapter/results/ResultPublisher.java) | class | Writes customer results and the IT report, then publishes ready manifests. Another process handles sending and email. |

## Test classes

These are verification code, not classes that run as part of production processing.

| Class | What it checks |
| --- | --- |
| [AdapterAndMappingTest](../src/test/java/com/jenbrick/fileadapter/AdapterAndMappingTest.java) | Checks delimited parsing, source names, conversions and required-field rules directly. |
| [AdditionalAdaptersTest](../src/test/java/com/jenbrick/fileadapter/AdditionalAdaptersTest.java) | Checks the other input formats and parsing edge cases. |
| [CustomerResultFormatsTest](../src/test/java/com/jenbrick/fileadapter/CustomerResultFormatsTest.java) | Reads customer result files back through adapters to check original values and appended code/remarks across formats. |
| [FlexibleProjectionIntegrationTest](../src/test/java/com/jenbrick/fileadapter/FlexibleProjectionIntegrationTest.java) | Runs actual files through Spring/H2 and compares saved JSON with expected bodies. |
| [FlexibleProjectionLiveTest](../src/test/java/com/jenbrick/fileadapter/FlexibleProjectionLiveTest.java) | Uses local HTTP services to check sent bodies, acknowledgement, retries and restart behavior. |
| [GroupMemberLookupTest](../src/test/java/com/jenbrick/fileadapter/GroupMemberLookupTest.java) | Checks group-before-member order, allowed request fields, shared group results and lookup failures/recovery. |
| [ProcessingResultsTest](../src/test/java/com/jenbrick/fileadapter/ProcessingResultsTest.java) | Checks HARD/MEDIUM/SOFT behavior, which contracts continue and which original rows are returned. |
| [RequestBatcherTest](../src/test/java/com/jenbrick/fileadapter/RequestBatcherTest.java) | Checks grouping/limits independently of JSON building, including scattered rows for the same contract. |
| [ScopedServiceResultsTest](../src/test/java/com/jenbrick/fileadapter/ScopedServiceResultsTest.java) | Checks service-reported member/contract failures, response matching and recovery without assuming HTTP success means every member succeeded. |

## Types declared inside those files

These smaller types live in the parent’s source file. They are data holders, settings, choices or private helpers, not separate processing stages.

| Type | What it holds or does |
| --- | --- |
| [IngestionProperties.Mode](../src/main/java/com/jenbrick/fileadapter/config/IngestionProperties.java) | Chooses PREVIEW or LIVE. |
| [IngestionProperties.Delivery](../src/main/java/com/jenbrick/fileadapter/config/IngestionProperties.java) | Holds endpoint, timeouts, attempt/replay rules, success statuses and optional response protocol; tokens stay separate. |
| [Profile.Format](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Chooses DELIMITED, FIXED_WIDTH, XML or JSON input. |
| [Profile.HeaderMode](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Says whether the first delimited record is a header. |
| [Profile.BlankPolicy](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Chooses omit, null, empty or reject for blank mapped values. |
| [Profile.ValueType](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Names supported JSON value types for checks. |
| [Profile.JsonMode](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Chooses one object, root array or JSON Lines input. |
| [Profile.Layout](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Describes input encoding, delimiter, columns, selectors and format-specific settings. |
| [Profile.FixedColumn](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Gives one fixed-width field its name, offset and length. |
| [Profile.FixedWidth](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Holds total fixed-width record length and column positions. |
| [Profile.Xml](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Holds the XML record path and namespaces. |
| [Profile.Json](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Holds the input JSON mode. |
| [Profile.SourceField](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Selects an input field and its permitted aliases. |
| [Profile.ScalarMapping](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Holds one value’s source, defaults, conversions, blank policy and code translations. |
| [Profile.ContractField](../src/main/java/com/jenbrick/fileadapter/config/Profile.java) | Describes one required/type/nullability check; “contract” here means data-shape rules. |
| [RuntimeConfiguration.WorkDirectoryLock](../src/main/java/com/jenbrick/fileadapter/config/RuntimeConfiguration.java) | Holds/releases the local lock that allows one process per work folder. |
| [ConfiguredGroupResolver.Request](../src/main/java/com/jenbrick/fileadapter/consent/ConfiguredGroupResolver.java) | The HTTP group request containing only externalGroupId. |
| [ConsentSettings.LookupMode](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | Chooses synthetic FIXTURE or HTTP lookup. |
| [ConsentSettings.Lookup](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | Holds member endpoint, timeouts and fixture list. |
| [ConsentSettings.Fixture](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | Pairs member-matching fields with a synthetic resolution. |
| [ConsentSettings.MemberIdentity](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | Carries internal contract, three-character PIN and member GUID. |
| [ConsentSettings.Resolution](../src/main/java/com/jenbrick/fileadapter/consent/ConsentSettings.java) | Carries owner/manager identities and optional legacy consent GUIDs. |
| [GroupSettings.Context](../src/main/java/com/jenbrick/fileadapter/consent/GroupSettings.java) | Carries internal group number and customer ID. |
| [GroupSettings.Lookup](../src/main/java/com/jenbrick/fileadapter/consent/GroupSettings.java) | Holds group endpoint, timeouts and fixtures. |
| [GroupSettings.Fixture](../src/main/java/com/jenbrick/fileadapter/consent/GroupSettings.java) | Maps an external group ID to synthetic group context. |
| [UpsertClient.DeliveryResult](../src/main/java/com/jenbrick/fileadapter/delivery/UpsertClient.java) | Carries overall outcome, HTTP status and structured service issues. |
| [UpsertClient.ServiceIssue](../src/main/java/com/jenbrick/fileadapter/delivery/UpsertClient.java) | Identifies a service issue’s severity, scope, contract/member and code. |
| [UpsertClient.Outcome](../src/main/java/com/jenbrick/fileadapter/delivery/UpsertClient.java) | Names acknowledged, retryable, rejected and uncertain delivery results. |
| [ProcessingLedger.Run](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java) | Represents the saved run and its current status. |
| [ProcessingLedger.Request](../src/main/java/com/jenbrick/fileadapter/processing/ProcessingLedger.java) | Represents a saved body, source references, attempt count and request status. |
| [Projection.Scope](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) | Chooses one request per record, whole file or complete groups. |
| [Projection.Batching](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) | Holds group keys, contracts/request, row/member caps and eligibility filter. |
| [Projection.Node](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) | Describes a JSON value, constant, object or array, with its conversions/checks. |
| [Projection.Collection](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) | Selects/group rows and describes the JSON item to build. |
| [Projection.Where](../src/main/java/com/jenbrick/fileadapter/projection/Projection.java) | Describes presence/equality/any-present filtering. |
| [ProjectionEngine.Request](../src/main/java/com/jenbrick/fileadapter/projection/ProjectionEngine.java) | Pairs the rendered JSON body with contributing source record numbers. |
| [ErrorPolicy.Severity](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | Names HARD, MEDIUM and SOFT findings. |
| [ErrorPolicy.Scope](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | Names file, contract, member or record scope. |
| [ErrorPolicy.Condition](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | Names MISSING and EQUALS validation conditions. |
| [ErrorPolicy.Rule](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | Holds a validation condition, severity and approved customer/internal wording. |
| [ErrorPolicy.ServiceCode](../src/main/java/com/jenbrick/fileadapter/results/ErrorPolicy.java) | Translates a service code into approved customer code/remarks and internal text. |
| [ResultStore.Source](../src/main/java/com/jenbrick/fileadapter/results/ResultStore.java) | Holds a saved original row/raw text and its source/resolved identities. |
| [ResultStore.Finding](../src/main/java/com/jenbrick/fileadapter/results/ResultStore.java) | Holds an error/warning with wording, affected source records and optional request reference. |
| [ScopedResponseReader.LimitedInputStream](../src/main/java/com/jenbrick/fileadapter/results/ScopedResponseReader.java) | Private helper that stops an oversized service response from being read without a bound. |
| [SourceResultWriter.Row](../src/main/java/com/jenbrick/fileadapter/results/SourceResultWriter.java) | Pairs an original source record with the code/remarks to return. |
| [XmlFileAdapter.Budget](../src/main/java/com/jenbrick/fileadapter/source/XmlFileAdapter.java) | Private helper that caps XML nodes/text while reading a record. |
| [RequestBatcherTest.Row](../src/test/java/com/jenbrick/fileadapter/RequestBatcherTest.java) | Small test-only contract/member/record-number example. |

## Important boundaries

- Group lookup uses external group ID. Member lookup gets member information and the returned group context, without consent/payer fields.
- Request grouping keeps complete contracts together; smaller requests do not remove the in-memory file limit.
- Save exact outgoing bodies before POST. An uncertain result needs investigation or an agreed duplicate-safe retry rule.
- Customer files use original records with code/remarks. IT gets the detailed report. Sending files/email happens outside this application.
- The optional scoped-response protocol is a POC example. The receiving-service team still needs to agree on actual JSON, completion, partial-failure and retry rules.
