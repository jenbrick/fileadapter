# Implemented architecture

## CMS consent extension

The [consent workflow](consent.md) adds member enrichment and one array request per member with populated preferences after normalization. Generic employee profiles retain configurable mapping and validation. Consent rules live in the immutable profile snapshot; resolved member plans live in H2. Delivery never looks members up again after READY. Request numbers identify POSTs; source record numbers identify member rows. One acknowledgement and retry identity cover the whole member array. A single POST does not establish service-side transaction atomicity. PREVIEW suppresses consent upserts but may perform read-only HTTP lookup; the explicit consent-demo profile uses synthetic fixtures instead.

Editable Mermaid source: [architecture.mmd](architecture.mmd).

```mermaid
flowchart TD
    subgraph Configuration["Configuration"]
        PROFILE["Versioned profile: routing, layout and mapping or consent rules"]
        SETTINGS["Delivery settings and execution mode"]
        SNAPSHOT["Immutable run snapshot; excludes bearer tokens"]
        PROFILE --> SNAPSHOT
        SETTINGS --> SNAPSHOT
    end

    subgraph Intake["Intake"]
        LAN["LAN filesystem mount + ready marker"] --> POLL["Scheduled directory poller"]
        CLI["One-shot file command"] --> PROCESS["File processor"]
        POLL --> PROCESS
        PROCESS --> STAGE["Copy input locally and hash content"]
        STAGE --> ID["Content + snapshot identity / duplicate check"]
    end
    SNAPSHOT --> ID

    subgraph Preflight["Whole-file preflight"]
        ID --> REGISTRY["Format adapter registry"]
        REGISTRY --> CSV["Delimited adapter: headed/headerless CSV, pipe, tab"]
        REGISTRY --> FIXED["Fixed-width adapter: headerless character ranges"]
        REGISTRY --> XML["XML adapter: record path + namespaces"]
        REGISTRY --> JSON["JSON adapter: object, array, lines"]
        CSV --> SOURCE["SourceRecord: logical number + JsonNode"]
        FIXED --> SOURCE
        XML --> SOURCE
        JSON --> SOURCE
        SOURCE --> NORMALIZE["Logical field and alias resolution"]
        NORMALIZE --> KIND{"Consent profile?"}
        KIND -->|No| MAP["Configurable nested mapping"]
        EXTENSION["Named Java transforms"] --> MAP
        MAP --> VALIDATE["Required paths, types, nullability"]
        KIND -->|Yes| CONSENT["Validate consent intents and coverage"]
        CONSENT --> LOOKUP["Resolve owner, manager and consent GUIDs"]
        LOOKUP --> PLAN["Freeze member consent entries and stage one array in H2"]
        PLAN --> REQUESTS
        VALIDATE --> REQUESTS[("Durably staged requests")]
        REQUESTS --> READY["Mark ready only after all records validate"]
    end

    subgraph Execution["Execution"]
        READY --> MODE{"Preview or live?"}
        MODE -->|Preview| JSONL["Named preview, source rows, targets and raw JSON Lines"]
        MODE -->|Live| SEND["Persist SENDING + POST using Jackson"]
        SEND --> API["Existing upsert API"]
        API --> OUTCOME{"Outcome"}
        OUTCOME -->|Acknowledged| ACK["Persist acknowledgement"]
        OUTCOME -->|Temporary/uncertain| SAFE{"Service guarantees replay safety?"}
        SAFE -->|Yes, budget remains| RETRY["Backoff + same idempotency key"]
        RETRY --> SEND
        SAFE -->|No or exhausted| PAUSE["Pause for operator action"]
        OUTCOME -->|Permanent rejection| ERROR["Quarantine + value-free report"]
        ACK --> NEXT{"More requests?"}
        NEXT -->|Yes| SEND
        NEXT -->|No| FINISH["Archive input and mark complete"]
        JSONL --> FINISH
    end
    VALIDATE -->|Invalid| ERROR
    CONSENT -->|Invalid| ERROR
    LOOKUP -->|Unresolved or failed| ERROR
    REGISTRY -->|Parse/unsupported format| ERROR

    LEDGER[("Local H2 processing ledger")]
    ID <--> LEDGER
    PLAN --> LEDGER
    REQUESTS --> LEDGER
    SEND --> LEDGER
    ACK --> LEDGER
    PAUSE --> LEDGER
    FINISH --> LEDGER
    LEDGER --> RECOVER["Recover active runs with saved snapshots"]
    RECOVER --> PROCESS
```

The H2 ledger stores file-level state, immutable configuration snapshots, staged request payloads, attempts, and acknowledgements. Local source copies and previews live under the configured work directory. There is no JSON intermediate file in the live POST path.

The implementation deliberately uses a single worker and sequential delivery. It provides restart recovery and replay safeguards, not a distributed exactly-once transaction. Delimited, fixed-width, XML, and JSON parsers are implemented. Full JSON Schema validation, row aggregation, byte-oriented fixed-width layouts, mixed-content XML, envelope JSON record selection, and asynchronous API completion remain extensions once their contracts are known.

Preview artifacts include a source-labelled JSON document alongside unchanged raw request JSON Lines. On startup, completed preview runs missing these artifacts are backfilled from the ledger without source parsing or HTTP delivery.
