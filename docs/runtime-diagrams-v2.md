# Current POC class and sequence diagrams

> V2: plain-English copy. [Original for comparison](runtime-diagrams.md). Commands, configuration examples and diagrams are unchanged.
These diagrams show the code that exists in the POC. For the smaller proposed January design, see the [January diagrams](january-release-diagrams-v2.md).

The POC calls the [group service before the member service](group-member-lookups-v2.md) and supports [both complete-contract JSON examples](contract-batching-v2.md). The happy path uses `GROUPED` mode. Whole-file (`FILE`) and one-record (`RECORD`) mapping are also available. “Projection” means the configured instructions for building the outgoing JSON.

The [CSV walkthrough](csv-debug-walkthrough-v2.md) gives the breakpoints, example values and commands.

The simple sequence assumes a new run, valid input, LIVE mode with HTTP lookups, successful preparation and an accepted consent request. Some labels combine related classes to keep the picture readable; they are not extra Java classes. The detailed sequences split preparation from delivery. Class diagrams show the main classes and dependencies, leaving out most Spring setup and configuration records.

## Successful flow: CSV to consent POST

```mermaid
sequenceDiagram
    autonumber
    participant I as CLI or DirectoryPoller
    participant P as FileProcessor
    participant M as Adapters and PreparationCoordinator
    participant L as Local stores
    participant H as HttpUpsertClient and endpoint
    I->>P: process(input, profileId, consume)
    P->>P: Resolve profile, copy input, hash and save snapshot
    P->>L: create run (STAGING)
    P->>M: Read CSV and save originals; normalize source names
    M->>M: Resolve external group ID first
    M->>L: Freeze internal group number and customer ID per run/group
    M->>M: Member-only demographics lookup with group context
    M->>L: Save enriched rows
    P->>M: project(rows, GROUPED projection)
    M->>M: Gather whole contracts and enforce row/member caps
    M-->>P: Single-contract objects or bounded contract arrays
    P->>L: Stage each body with its source rows; status READY
    P->>L: status DELIVERING
    loop Each staged request
        P->>L: Mark request SENDING
        P->>H: post(saved body, runId:requestNumber, settings)
        H-->>P: ACKNOWLEDGED
        P->>L: Save request acknowledgement
    end
    P->>L: status FINALIZING
    P->>P: Archive input and snapshot
    P->>P: Publish internal results and optional customer result file
    P->>L: status COMPLETED
    P-->>I: runId
```

Editable diagram: [sequence-happy-path.mmd](diagrams/sequence-happy-path.mmd).

## Read and prepare the file, then choose PREVIEW or LIVE

```mermaid
sequenceDiagram
    autonumber
    participant I as CLI or DirectoryPoller
    participant P as FileProcessor
    participant M as Parsing and projection collaborators
    participant L as Local stores
    participant O as PreviewWriter or UpsertClient
    Note over I,P: Poller first calls recover(); waits for ready marker and minimum age
    I->>P: process(input, profileId, consume)
    alt Invalid input or no unique matching profile
        P-->>I: Throw intake exception
        Note over I,P: Poller quarantines remaining input for IllegalArgumentException; CLI exits 1
    else Intake accepted
        P->>P: Copy, checksum, derive ID from content and snapshot
        P->>L: Get run; create STAGING run if absent
        opt Poller consumes source
            P->>P: Delete ready marker, then incoming file
        end
        P->>P: execute(id, false)
        alt COMPLETED or PREVIEWED
            Note over P,O: Return success; recreate missing preview if needed
        else FAILED or PAUSED
            Note over P,L: FAILED returns false; PAUSED needs explicit resume
        else FINALIZING
            P->>P: finish() without parsing or sending
        else STAGING, READY or DELIVERING
            opt STAGING
                P->>P: Verify staged checksum
                P->>L: Clear partially staged requests
                loop Each data record
                    P->>M: Read original rows; enforce file limit; save originals
                    M->>M: Normalize and evaluate configured severity rules
                    M->>M: Validate input contract
                    M->>L: Check saved enrichment by run and row
                    alt Saved enriched row exists
                        L-->>M: Reuse enriched payload
                    else Not yet saved
                        M->>M: Resolve group context, then member-only demographics
                        M->>L: Save group context per run/group and enriched row
                    end
                end
                P->>M: project using FILE, RECORD or GROUPED scope
                M->>M: GROUPED gathers contracts, checks caps and partitions requests
                alt HARD parse, lookup, limit, mapping or validation failure
                    M-->>P: Exception
                    P->>P: fail(PREFLIGHT); report; move processing files to errors
                    P->>L: FAILED
                    Note over P,O: Stop this run; no upsert is sent
                else Preparation succeeds or MEDIUM isolates bad contracts
                    Note over M,L: MEDIUM saves contract findings and removes rejected contract rows; SOFT retains rows
                    M-->>P: One contract or bounded contract arrays with source provenance
                    P->>L: Stage requests; READY
                end
            end
            Note over P,O: Continue only if preparation succeeded
            alt PREVIEW
                P->>O: write(run, snapshot)
                P->>L: FINALIZING
                P->>P: Archive and publish results; mark PREVIEWED or PREVIEWED_WITH_ERRORS
            else LIVE
                P->>L: DELIVERING; read staged requests in order
                loop Each request until failure or pause
                    alt Already ACKNOWLEDGED
                        Note over P,L: Skip this request
                    else Pending or previously attempted
                        P->>P: deliver(id, request, snapshot, manual)
                        Note over P,O: See delivery and retry sequence below
                        P->>O: Deliver saved body under replay policy
                        O-->>P: Outcome
                    end
                end
                opt Every request acknowledged or no requests generated
                    P->>L: FINALIZING
                    P->>P: Archive and publish results; mark COMPLETED or COMPLETED_WITH_ERRORS
                end
            end
        end
    end
    Note over P,L: Customer output uses saved originals plus code/remarks; internal JSON keeps all findings
    Note over P,L: Recovery selects STAGING, READY, DELIVERING, FINALIZING; infrastructure errors may leave these recoverable
```

Editable diagram: [sequence-detailed.mmd](diagrams/sequence-detailed.mmd).

## Handle HTTP results, retries and restart

```mermaid
sequenceDiagram
    autonumber
    participant P as FileProcessor
    participant L as ProcessingLedger
    participant H as HttpUpsertClient
    participant S as Upsert endpoint
    Note over P,L: Each request is one contract or a bounded array; acknowledged earlier requests are skipped
    alt Request already REJECTED
        P->>P: fail(HTTP_REJECTED); stop
    else Prior attempt exists and replay-safe is false
        P->>P: pause(); reconciliation required
    else Attempt permitted
        loop While retry budget remains and no terminal outcome
            P->>L: sending() persists SENDING and increments attempts
            P->>H: post(saved JsonNode, runId:requestNumber, settings)
            H->>S: POST application/json with configured headers
            alt Configured success status (default 200, 201, 204)
                S-->>H: Acknowledgement status
                H-->>P: ACKNOWLEDGED plus optional validated scoped issues
            else HTTP 408, 429 or 500 and above
                S-->>H: Retryable status
                H-->>P: RETRYABLE
            else Other 2xx or network ResourceAccessException
                H-->>P: UNCERTAIN
            else Other HTTP status
                S-->>H: Rejection status
                H-->>P: REJECTED
            end
            P->>L: Persist result and structured issues together
            alt ACKNOWLEDGED
                P->>P: Correlate saved issues to request source rows
                Note over P,L: Known member/contract rejection records findings; other members continue
                Note over P,L: Unknown correlation pauses; HARD service stop fails remaining work
            else REJECTED
                P->>P: fail(HTTP_REJECTED); stop run
            else Replay disabled
                P->>P: PAUSED; stop run
            else More attempts available
                P->>P: Exponential delay capped at 30 seconds
                Note over P,H: Retry same complete contract batch and same idempotency key
            else Budget exhausted
                P->>P: PAUSED; stop run
            end
        end
    end
    opt Explicit resume of a PAUSED run
        P->>L: Load saved snapshot and requests
        Note over P,L: Skip acknowledgements; no parsing or lookup for delivery-phase resume
        Note over P,H: Manual resume adds a retry budget; replay-safe false still blocks prior attempts
    end
```

Editable diagram: [sequence-delivery-recovery.mmd](diagrams/sequence-delivery-recovery.mmd).

## Classes that coordinate the work

```mermaid
classDiagram
    direction TB
    class FileAdapterApplication {
        +main(args)
    }
    class DirectoryPoller {
        +poll()
    }
    class FileProcessor {
        +process(input, profileId, consume) String
        +resume(id) boolean
        +recover()
        -execute(id, manual) boolean
        -deliver(id, request, snapshot, manual) boolean
        -finish(run, snapshot) boolean
    }
    class ProfileCatalog {
        +resolve(file, explicitId) Profile
    }
    class PreparationCoordinator {
        +prepare(id, raw, profile, mode) List
    }
    class ProcessingLedger
    class PreviewWriter
    class UpsertClient {
        <<interface>>
        +post(request, idempotencyKey, settings) DeliveryResult
    }
    class HttpUpsertClient
    FileAdapterApplication ..> FileProcessor : starts or resumes
    DirectoryPoller --> FileProcessor : polls and recovers
    FileProcessor --> ProfileCatalog
    FileProcessor --> PreparationCoordinator
    FileProcessor --> ProcessingLedger
    FileProcessor --> PreviewWriter
    FileProcessor --> UpsertClient
    UpsertClient <|.. HttpUpsertClient
```

Editable diagram: [classes.mmd](diagrams/classes.mmd).

## File-reading classes

```mermaid
classDiagram
    direction TB
    class FileProcessor
    class PreparationCoordinator
    class AdapterRegistry {
        +require(format) FileFormatAdapter
    }
    class FileFormatAdapter {
        <<interface>>
        +format() Format
        +read(file, layout, consumer)
    }
    class DelimitedFileAdapter
    class FixedWidthFileAdapter
    class XmlFileAdapter
    class JsonFileAdapter
    class SourceRecord {
        +number() long
        +payload() JsonNode
        +rawText() String
    }
    class SourceNormalizer {
        +normalize(source, layout) SourceRecord
    }
    FileProcessor --> AdapterRegistry
    PreparationCoordinator --> SourceNormalizer
    AdapterRegistry --> FileFormatAdapter : selects by format
    FileFormatAdapter <|.. DelimitedFileAdapter
    FileFormatAdapter <|.. FixedWidthFileAdapter
    FileFormatAdapter <|.. XmlFileAdapter
    FileFormatAdapter <|.. JsonFileAdapter
    FileFormatAdapter ..> SourceRecord : emits via consumer
    SourceNormalizer ..> SourceRecord : preserves record number
```

Editable diagram: [classes-adapters.mmd](diagrams/classes-adapters.mmd).

## Mapping and lookup classes

```mermaid
classDiagram
    direction TB
    class ProjectionEngine {
        +validate(projection)
        +enrich(runId, row, profile, mode) SourceRecord
        +project(rows, projection) List
        -render(node, rows, used, path) JsonNode
    }
    class Projection
    class MappingValues {
        +resolve(source, field) JsonNode
    }
    class TransformRegistry
    class RequestValidator
    class EnrichedRecordStore
    class RecordEnricher {
        <<interface>>
        +name() String
        +validate(profile)
        +enrich(source, profile, mode) SourceRecord
    }
    class ConsentMemberEnricher
    class MemberResolver {
        <<interface>>
        +resolve(memberPayload, settings) Resolution
    }
    class ConfiguredMemberResolver
    ProjectionEngine ..> Projection : interprets template
    ProjectionEngine --> MappingValues
    ProjectionEngine --> RequestValidator
    ProjectionEngine --> EnrichedRecordStore
    ProjectionEngine --> RecordEnricher : selects by name
    MappingValues --> TransformRegistry
    RecordEnricher <|.. ConsentMemberEnricher
    ConsentMemberEnricher --> MemberResolver
    MemberResolver <|.. ConfiguredMemberResolver
```

Editable diagram: [classes-projection.mmd](diagrams/classes-projection.mmd).

## Request-grouping classes

```mermaid
classDiagram
    direction TB
    class PreparationCoordinator
    class ProjectionEngine {
        +project(rows, projection) List
        -groupKey(row, fields) List
        -projectOne(rows, projection, requests)
    }
    class RequestBatcher {
        +partition(rows, groupKey, memberKey, maxGroups, maxRecords, maxMembers) List
    }
    class Projection {
        +scope() Scope
        +batching() Batching
    }
    class Batching {
        +groupBy() List
        +maxGroupsPerRequest() int
        +maxRecordsPerGroup() int
        +memberKey() String
        +maxMembersPerGroup() Integer
    }
    PreparationCoordinator --> ProjectionEngine : bounded enriched file
    ProjectionEngine ..> RequestBatcher : pack complete groups
    ProjectionEngine ..> Projection : read configuration
    Projection --> Batching : optional for GROUPED scope
```

Editable diagram: [classes-batching.mmd](diagrams/classes-batching.mmd).

`RequestBatcher` gathers rows for the same group and checks group/member limits. It keeps each group together and does not decide the consent JSON structure. `ProjectionEngine` builds the JSON for each chosen set of rows. Each request is saved separately and has its own retry key. All file preparation finishes before sending begins.

## Errors, warnings and result files

```mermaid
sequenceDiagram
    autonumber
    participant P as FileProcessor
    participant C as Preparation and service result handlers
    participant L as Ledger and ResultStore
    participant R as ResultPublisher and source-format writer
    participant O as Mounted outboxes
    P->>L: Capture original rows before normalization
    P->>C: Validate, resolve group then member, build bounded requests
    alt HARD preparation failure
        C->>L: Record file-level finding and all parsed row references
        Note over P,C: Stop file before POST; preserve original input
    else MEDIUM contract failure
        Note over P,C: A failed group lookup never calls member service
        C->>L: Record finding for every row in that contract
        C-->>P: Requests for viable contracts only
    else SOFT warning
        C->>L: Record warning; retain rows for delivery
        C-->>P: Continue normal requests
    end
    opt LIVE acknowledged scoped response
        P->>L: Persist acknowledgement plus structured service issues
        P->>C: Correlate contract/member within request origins
        alt Known member and approved code
            C->>L: Finding for that member's original rows only
        else Unknown identity, unmapped code or incomplete decision
            C-->>P: Pause for reconciliation; infer no customer rejection
        end
    end
    P->>R: Publish saved findings and run status
    R->>L: Read original rows and all severity findings
    R->>O: Write immutable internal JSON revision
    opt Selected customer rows
        R->>O: Original input format plus code and remarks fields
    end
    alt Complete source and publishable results
        R->>O: Atomic ready manifests naming revision files
    else PAUSED, incomplete parsing or result export error
        R->>O: Internal diagnostic manifest; no customer-ready manifest
    end
    Note over R,O: Outside process sends customer files and emails IT; honor PREVIEW mode
```

Editable diagram: [sequence-error-results.mmd](diagrams/sequence-error-results.mmd).

## Customer-result and IT-report classes

```mermaid
classDiagram
    direction TB
    class FileProcessor
    class PreparationCoordinator {
        +prepare(id, raw, profile, mode) List
    }
    class ResultStore {
        +capture(id, source)
        +identify(id, row, contractKey, contractId, memberGuid)
        +add(id, finding)
        +sources(id) List
        +findings(id) List
    }
    class ResultPublisher {
        +publish(run, snapshot)
    }
    class SourceResultWriter {
        +write(path, layout, policy, rows)
    }
    class ServiceResultProcessor {
        +apply(id, request, policy) boolean
    }
    class ScopedResponseReader {
        +read(input) List
    }
    class HttpUpsertClient
    class ProcessingLedger
    FileProcessor --> PreparationCoordinator
    FileProcessor --> ResultPublisher
    FileProcessor --> ServiceResultProcessor
    PreparationCoordinator --> ResultStore
    ResultPublisher --> ResultStore
    ResultPublisher --> SourceResultWriter
    ServiceResultProcessor --> ResultStore
    ServiceResultProcessor ..> ProcessingLedger : persisted request issues
    HttpUpsertClient ..> ScopedResponseReader : opt-in protocol
```

Editable diagram: [classes-results.mmd](diagrams/classes-results.mmd).

## Group lookup followed by member lookup

```mermaid
sequenceDiagram
    autonumber
    participant E as ProjectionEngine
    participant G as GroupEnricher and GroupResolver
    participant GS as Existing group service facade
    participant M as ConsentMemberEnricher and MemberResolver
    participant MS as Member demographics service facade
    E->>G: enrichForRun(runId, normalized row)
    alt Row has no consent changes
        G-->>E: Unchanged row; no group lookup
    else Active member row
        G->>G: Read EXTERNAL_GROUP_ID; check saved run/group context
        alt Context already saved for this run/group
            G->>G: Reuse internal group number and customer ID
        else First use of this group
            G->>GS: POST externalGroupId only
            GS-->>G: internalGroupNumber and customerId
            G->>G: Validate and persist group context before member lookup
        end
        G-->>E: Row plus resolvedGroup
    end
    alt Valid group context and active member row
        E->>M: enrich(row including resolvedGroup)
        M->>M: Build typed member-only payload
        Note over M,MS: Demographics/member identifiers plus internalGroupNumber and customerId
        M->>MS: POST MemberLookupPayload only
        MS-->>M: Internal contract, PIN and member GUID resolution
        M->>M: Validate identities; add resolved owner and manager
        M-->>E: Enriched record ready for projection
        E->>E: Save enriched row; build configurable consent JSON later
    else Group failure or incomplete context
        E->>E: Apply HARD or MEDIUM enrichment policy; do not call member service
    else No-change row
        E->>E: Neither lookup is required
    end
    Note over G,MS: No payer/coverage data, consent preferences or upsert JSON enters member lookup
```

Editable diagram: [sequence-group-member.mmd](diagrams/sequence-group-member.mmd).

## Group/member service classes and interfaces

```mermaid
classDiagram
    direction TB
    class ProjectionEngine
    class GroupEnricher {
        +enrichForRun(runId, source, profile, mode) SourceRecord
    }
    class ConsentMemberEnricher {
        +enrich(source, profile, mode) SourceRecord
    }
    class GroupResolver {
        <<interface>>
        +resolve(externalGroupId, settings) Context
    }
    class ConfiguredGroupResolver
    class GroupContextStore
    class MemberResolver {
        <<interface>>
        +resolve(memberPayload, settings) Resolution
    }
    class ConfiguredMemberResolver
    class MemberLookupPayload
    ProjectionEngine --> GroupEnricher : first
    ProjectionEngine --> ConsentMemberEnricher : second
    GroupEnricher --> GroupResolver
    GroupEnricher --> GroupContextStore
    GroupResolver <|.. ConfiguredGroupResolver
    ConsentMemberEnricher --> MemberResolver
    ConsentMemberEnricher ..> MemberLookupPayload : nine allowed properties
    MemberResolver <|.. ConfiguredMemberResolver
```

Editable diagram: [classes-group-member.mmd](diagrams/classes-group-member.mmd).

## What the alternate paths mean

- Spring checks configuration before processing files. An invalid layout, unknown enricher or broken JSON template can stop startup.
- A preparation error stops that run’s preparation. The later PREVIEW/LIVE branches apply only if preparation succeeds.
- By default, HTTP status codes determine acknowledgement. The optional `SCOPED_V1` response reader also checks completed member/contract results and matches them to original records. Neither choice says that all consents are saved together as one transaction. See [error handling](error-handling-v2.md).
- Startup recovery considers `STAGING`, `READY`, `DELIVERING` and `FINALIZING` runs. `PAUSED` needs explicit resume. `FAILED` is not resumed. Completed runs, including `_WITH_ERRORS`, are reused for identical content and saved configuration. Saved originals and findings allow result files to be recreated.
- PREVIEW can use real HTTP lookups when configured. The default demo uses fixtures and makes no group, member or upsert HTTP calls. LIVE refuses fixture lookup at either stage. A saved group result is reused within its run; delivery recovery needs neither lookup.
- H2 on disk saves configuration, enriched rows and request JSON. Reusing a stable request key prevents duplicate effects only if the receiving service has agreed to honor it.
