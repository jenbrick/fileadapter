# Customer results and internal diagnostics

The POC writes two separate outboxes. An outside process owns customer transmission and IT email; this application sends neither. Configure mounted LAN directories with `CUSTOMER_RESULTS_DIR` and `INTERNAL_RESULTS_DIR`, or use the defaults below the work directory.

## Severity and processing boundaries

| Severity | Preparation behavior | Customer rows by default |
| --- | --- | --- |
| HARD | Fail the full file before any upsert | All successfully parsed original rows, with rejection code/remarks |
| MEDIUM | Reject one complete contract and continue with other contracts | All original rows in the rejected contract |
| SOFT | Record a warning and continue | No rows, unless `include-warnings: true` |

A service can additionally identify a rejected **member** in a contract. With the opt-in response adapter described below, that returns only that member’s original rows; it does not automatically reject the other members. If the member occupied several input rows, all of those source rows in the submitted request are returned. This POC does not infer which individual coverage row failed from a member GUID alone.

Hard preparation errors are detected before the first upsert. Once LIVE delivery has begun, previously acknowledged requests cannot be rolled back. A later hard stop returns explicitly rejected and not-completed rows, **excluding earlier acknowledged rows**. Transport ambiguity or an uncorrelatable response pauses the run for reconciliation; it is not evidence that a particular member failed. The internal report marks that blocking operational issue HARD with no customer row references.

Partial preparation/delivery ends in `PREVIEWED_WITH_ERRORS` or `COMPLETED_WITH_ERRORS`, rather than pretending every member succeeded. These are terminal runs: reprocessing the same content/configuration does not resend accepted contracts. The CLI exits 0 when processing is complete, including these partial outcomes; inspect the manifest/status and diagnostics for rejections. HARD file failure exits 1. Warning-only runs keep the ordinary PREVIEWED/COMPLETED status.

## Customer file: original record plus two fields

The customer file uses **original parsed source values**, before normalization, enrichment or JSON mapping. It adds:

```text
RESULT_CODE
RESULT_REMARKS
```

For CSV:

```csv
EXTERNAL_CONTRACT_ID,FIRST_NAME,LAST_NAME,RESULT_CODE,RESULT_REMARKS
EXT-CONTRACT-A,John,Smith,MEMBER_REJECTED,Member details require correction.
```

The real return contains all the original columns, not only the three shown here. The consent request can contain several members and several payer consents per member, while the returned file still has one result for each affected original source row.

| Input format | Customer result format |
| --- | --- |
| Headered CSV / pipe / semicolon / tab | Same delimiter, original columns, then code and remarks columns |
| Headerless delimited | Same physical column order with code and remarks appended; no new header |
| Fixed width | Entire original record retained, including gaps and padding, followed by code and remarks fields |
| XML | Original record fields/attributes plus code and remarks child elements, under the configured record path |
| JSON root array | Array of original record objects with code and remarks properties added |
| JSON root object | Original object with the two properties added |
| JSON Lines | One affected original object per line, with the two properties added |

Encoding comes from the input layout. Delimited quoting/newlines, JSON whitespace/property order and XML prefixes/container formatting can be serialized differently; field values and record structure are preserved. XML document metadata outside selected records is not a source record and is not copied. The minimal configured container path is rebuilt. Fixed-width original text is saved separately so unmodeled gaps are preserved, not reconstructed from mapped fields.

Fixed-width defaults append 16 Unicode code points for code and 160 for remarks, both space padded. Configure `code-width` / `remarks-width` to increase those widths. They must be at least 16 / 160. Nothing is silently truncated. Use the original input record length plus those widths to describe the return layout to the downstream consumer.

Field names are configurable through `code-field` and `remarks-field`. An existing source field of either name is a collision: result export fails rather than overwriting customer data. Unrepresentable output characters, field overflow or XML root constraints also block publication and appear in internal diagnostics. A JSON OBJECT or XML record that is itself the document root cannot represent several independent selected records; those layouts accept at most the one original record.

A source row appears once in the customer file. If it has several findings, HARD takes precedence over MEDIUM, then SOFT; codes sort alphabetically to make same-severity selection deterministic. All findings remain in the IT file. Customer remarks are approved configuration or fixed application text; exception messages and arbitrary service remarks never become customer text.

Defaults return failed records only. `include-warnings: true` includes warning rows without blocking them. `include-success: true` additionally includes other source rows with code OK after terminal completion, useful for a full result feed. In PREVIEW the OK remarks explicitly state that nothing was delivered. No customer file is created when no rows qualify.

## Output folders and handoff

Default layout:

```text
<work>/results/customer/<run-id>/
  contract-batches.results.<revision>.csv
  ready.json

<work>/results/internal/<run-id>/
  diagnostics.<revision>.json
  ready.json
```

The extension follows the input; `.psv`, `.tsv`, `.fw`, `.xml`, `.json` and `.jsonl` retain their format. Configure destinations in Git Bash before running:

```bash
export CUSTOMER_RESULTS_DIR='//server/share/customer-outbox'
export INTERNAL_RESULTS_DIR='//server/share/it-outbox'
```

The folders must already be accessible as mounted filesystem paths. Credentials and email destinations are outside this POC. On Linux use the mounted share’s local path.

Consumers should watch **ready.json**, not temporary files or directory arrival. It names an immutable revision file and includes the run ID, mode, status, completeness and customerReady flag. Files are written to temporary names, then renamed before the manifest is published. A revised result uses a new filename; consumers should track `(runId, revision)` to avoid duplicate transmission. A PREVIEW manifest is a demonstration artifact and must not be sent as a real LIVE processing result.

PAUSED runs have internal diagnostics, but no customer-ready manifest. Their outcomes still require reconciliation. During a malformed/oversized input or failed profile selection, not all records can necessarily be parsed. The original input is retained in the error area; parseable-prefix results may exist for IT review, but **no incomplete customer return is published as ready**. No synthetic member records are invented for unreadable lines. Intake failure without a usable profile gets an internal HARD report and a reference to the quarantined original.

Result events and original source records are stored in file-backed H2. Repeated resume/recreation uses the saved rows and events, not edited source files. Findings have stable deduplication keys; persisted service outcomes are reapplied after a crash, even when the corresponding request was already acknowledged. Missing result artifacts can be regenerated on startup or when a completed run is revisited. Older saved runs created before source capture cannot acquire original-record returns retroactively.

The internal JSON includes every hard, medium and soft finding, stage/reason, approved customer code/remarks, request number when applicable, source record numbers, run status, input filename, completeness and severity counts. It does not dump bearer tokens or raw HTTP response bodies. Original records and identity references remain sensitive data; the outboxes should use the same access controls as the intake data.

## Configure validation and contract isolation

Errors are profile-specific and saved in the run snapshot. With no `errors` section, existing parsing/enrichment/projection failures retain hard-file behavior. Example:

```yaml
errors:
  contract-key: EXTERNAL_CONTRACT_ID
  enrichment-severity: MEDIUM
  projection-severity: MEDIUM
  code-field: RESULT_CODE
  remarks-field: RESULT_REMARKS
  code-width: 16
  remarks-width: 160
  include-warnings: false
  include-success: false
  rules:
    - source: FIRST_NAME
      condition: MISSING
      severity: MEDIUM
      code: BAD_MEMBER
      customer-remarks: Member details require correction.
      internal-remarks: Required first name was absent before lookup.
```

Rules run on normalized source fields before lookup. Conditions are `MISSING` (missing/null/blank) and `EQUALS` with `equals-value`. These are simple POC validation hooks, not a full rules language. Remarks are configured literal text. SOFT rules warn; they cannot make a failed parser, enrichment or JSON projection succeed.

MEDIUM enrichment errors reject the configured source contract key. This key must be a stable, unambiguous contract identifier shared by its members and repeated rows. If a required contract identity is unavailable, handling escalates to HARD. If aliases/producers identify the same contract differently, normalize them to a common key first; a failed lookup cannot discover an alias it never resolved.

MEDIUM projection handling requires GROUPED scope. Each resolved contract is checked independently before viable contracts are packed into final requests. A bad date, conflicting grouped value, or per-contract row/member limit can reject just that contract. Input syntax corruption, file record-limit overflow, missing grouping identity and infrastructure failures remain hard-file errors. All preparation finishes before any upsert.

`resolved-contract-key` and `resolved-member-key` default to `resolved.owner.contract` and `resolved.owner.contractMemberGuid`; configure them if another enricher uses different source paths. These source-side identities drive response correlation, independent of outgoing JSON nesting.

## Opt-in member/contract service results

The actual endpoint’s response contract remains undecided. By default the HTTP client retains status-code acknowledgement. Enable this **POC response protocol** only for a compatible test service:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=error-demo-service-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv \
  --ingestion.mode=LIVE \
  --ingestion.delivery.response-contract=SCOPED_V1
```

As with other LIVE demos, configure HTTP member lookup and the actual test URLs; fixture lookup is refused in LIVE. A configured successful HTTP status must have a body such as:

```json
{
  "complete": true,
  "results": [
    {
      "severity": "MEDIUM",
      "scope": "MEMBER",
      "contractId": "123456789",
      "memberGuid": "MEMBER-GUID-002",
      "code": "MEMBER_INVALID"
    }
  ]
}
```

`complete: true` is an explicit protocol guarantee: within this submitted request, all processing decisions are final, every rejection/warning is listed, and all remaining members succeeded. An empty results array means all succeeded. Without that guarantee, absence of a member error cannot be treated as success. This protocol must be agreed with the service; HTTP 200 alone does not establish it.

Supported combinations are HARD/FILE, MEDIUM/CONTRACT, MEDIUM/MEMBER and SOFT warnings at FILE/CONTRACT/MEMBER scope. Contract/member identities must match source provenance **in that request**, not just any row in the file. All issues are validated before any finding is recorded. Incomplete/malformed/oversized responses, unmapped result codes or unknown identities pause processing; no customer rejection is inferred. An already acknowledged response needing reconciliation is not POSTed again automatically.

Configure approved translations under the profile:

```yaml
errors:
  service-codes:
    MEMBER_INVALID:
      customer-code: MEMBER_REJECTED
      customer-remarks: Member details require correction.
      internal-remarks: Service rejected the member; inspect the source record references.
```

The service’s raw remarks are ignored. Each member-level rejection maps to all that member’s submitted original rows, including its repeated payer-consent/coverage rows. Contract rejection maps to all submitted member rows in that contract. Distinguishing a single consent or coverage row would require an additional agreed stable key in the response; do not guess from array position.

The adapter persists structured issue identities/codes alongside request acknowledgement before generating results. If a later request pauses, resume skips acknowledged requests, reapplies their saved findings without duplication and sends only unresolved requests under the saved replay policy.

## Run the examples

```bash
./gradlew test bootJar
bash scripts/run-error-examples.sh
# Includes all format/mapping/error demos and verifies customer fixtures:
python scripts/verify-examples.py
```

The three preview examples use the same interleaved 8-row CSV:

| Profile | Demonstration | Customer output |
| --- | --- | --- |
| `error-demo-hard-v1` | HARD validation stops the file | All 8 records; no requests staged |
| `error-demo-medium-v1` | John’s validation rejects EXT-CONTRACT-A; Morgan warns | 4 contract-A rows; other contracts proceed |
| `error-demo-soft-v1` | Morgan warns; `include-warnings` enabled | Morgan’s 2 rows; all contracts proceed |
| `error-demo-service-v1` | Approved response-code mappings, no artificial validation rules | Intended for compatible LIVE service tests |

Expected customer files are [hard](../examples/flexible/expected-customer-hard.csv), [medium](../examples/flexible/expected-customer-medium.csv) and [soft](../examples/flexible/expected-customer-soft.csv). The hard example intentionally exits 1; the runner accounts for that. Tests round-trip customer output through all 11 input layouts and exercise member-versus-contract responses and recovery.
