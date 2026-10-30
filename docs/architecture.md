# Implemented architecture

The [developer walkthrough](developer-walkthrough.md) is the primary team guide. [Flexible mappings](flexible-mappings.md) documents the new generic projection strategy and its latest-sample examples.

```mermaid
flowchart TD
    Config["Versioned profile: layout, enrichers, projection and delivery"] --> Snapshot["Immutable run snapshot"]
    LAN["LAN mount and ready marker"] --> Intake["Scheduled poller or one-shot CLI"]
    Intake --> Copy["Copy source locally and hash content"]
    Snapshot --> Identity["Content plus snapshot run identity"]
    Copy --> Identity
    Identity --> Adapter["FileFormatAdapter registry"]
    Adapter --> Normalize["Normalize physical fields and aliases"]
    Normalize --> Enrich["Named read-only RecordEnrichers"]
    Enrich --> Frozen["Persist enriched records in H2"]
    Frozen --> Build["Configured object trees and filtered grouped collections"]
    Build --> Validate["Check group consistency, required fields and limits"]
    Validate --> Stage["Stage complete request bodies and source provenance"]
    Stage --> Ready["Entire file ready before delivery"]
    Ready --> Mode{"PREVIEW or LIVE"}
    Mode --> Preview["Named preview plus exact request JSON Lines"]
    Mode --> Send["Persist SENDING and POST JsonNode"]
    Send --> Outcome{"HTTP outcome and replay policy"}
    Outcome -->|Acknowledged| Ack["Save request acknowledgement"]
    Outcome -->|Safe replay| Retry["Retry saved body and key"]
    Retry --> Send
    Outcome -->|Unsafe or exhausted| Pause["Pause with all contributing row numbers"]
    Outcome -->|Rejected| Error["Fail with value-free report"]
    Validate -->|Invalid| Error
    Ack --> Finish["Archive after all requests complete"]
    Preview --> Finish
    Ledger[("Local H2 ledger and enriched records")]
    Identity --> Ledger
    Frozen --> Ledger
    Stage --> Ledger
    Ack --> Ledger
    Ledger --> Recovery["Recover using saved snapshot and payloads"]
    Recovery --> Phase{"Saved phase"}
    Phase -->|STAGING| Adapter
    Phase -->|READY or DELIVERING| Mode
    Phase -->|FINALIZING| Finish
```

Editable source: [architecture.mmd](architecture.mmd).

File syntax stays inside adapters. Optional named enrichers add source-side values without choosing output properties. Projection profiles select object structure, collection filtering/grouping, scalar conversions and FILE/RECORD request scope. No consent target, coverage or manager path is built into the generic projection engine.

Enriched rows and final request bodies are persisted separately. Preflight validates the entire bounded file before any upsert. Recovery reuses saved configuration, enrichment and payloads, with aggregate source provenance. Projection snapshots use engine version 4. The older object-path mapper and specialized consent planner have been removed; engine versions 1–3 require the previous release.

The implementation is a bounded single-worker POC with local H2. FILE scope demonstrates aggregation across rows, not arbitrary-volume streaming. The service still owns batch semantics, acknowledgement, idempotency and business-level conflict rules. A JSON envelope is not a distributed transaction.
