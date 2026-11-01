# Customer error files and the IT report

> V2: plain-English copy. [Original for comparison](error-handling.md). Commands, configuration examples and diagrams are unchanged.
The POC writes two sets of files: customer results and a detailed internal report. Another process sends the customer file and emails IT. Configure accessible LAN folders through `CUSTOMER_RESULTS_DIR` and `INTERNAL_RESULTS_DIR`, or use the defaults under the work folder.

## What each error level does

| Level | During preparation | Customer records returned by default |
| --- | --- | --- |
| HARD | Stop the file before any consent upsert | Original parsed records with code/remarks, but publish only if the source return is complete |
| MEDIUM | Reject that whole contract and continue with others | All original rows for the rejected contract |
| SOFT | Record a warning and continue | None, unless `include-warnings: true` |

The optional service-response reader can also handle a rejected **member**. It returns that member’s submitted original rows without automatically rejecting everyone else on the contract. If the member had several rows, all of their rows in that request are returned. A member GUID alone cannot tell us which individual coverage row failed.

Preparation finishes before the first upsert. Once LIVE sending starts, a later stop cannot undo earlier accepted requests. A later HARD stop returns explicitly rejected and not-completed rows, excluding already acknowledged rows. If a send is uncertain or a response cannot be matched reliably, pause for investigation. That is an internal HARD operational issue with no customer row references; it does not prove a member failed.

A completed run with rejections becomes `PREVIEWED_WITH_ERRORS` or `COMPLETED_WITH_ERRORS`. Repeating the same content/configuration does not resend its accepted work. The command exits 0 for completed runs, including those with partial failures. Inspect status, manifests and diagnostics to see rejections. HARD file failure exits 1. Warning-only runs remain `PREVIEWED` or `COMPLETED`.

## Customer file: original records plus code and remarks

Use the saved source values from before normalization, lookup or JSON building. Add:

```text
RESULT_CODE
RESULT_REMARKS
```

A shortened CSV example:

```csv
EXTERNAL_CONTRACT_ID,FIRST_NAME,LAST_NAME,RESULT_CODE,RESULT_REMARKS
EXT-CONTRACT-A,John,Smith,MEMBER_REJECTED,Member details require correction.
```

The actual return includes all original columns. One JSON request may contain several members and consents, but the customer file still has one result for each affected original row.

| Input | Customer return |
| --- | --- |
| Headered CSV, pipe, semicolon or tab | Same delimiter and original columns, followed by code/remarks |
| Headerless delimited | Same column order, with code/remarks appended and no new header |
| Fixed width | Entire original line, including padding/gaps, followed by code/remarks fields |
| XML | Original record fields/attributes plus code/remarks child elements under the configured record path |
| JSON array | Original record objects with code/remarks properties |
| JSON object | Original object with code/remarks properties |
| JSON Lines | One affected original object per line with code/remarks properties |

Use the input layout’s encoding. Field values and record structure are preserved, but quoting, line endings, JSON spacing/property order and XML prefixes/container formatting may differ. XML metadata outside the selected records is not copied; only the required container path is rebuilt. Fixed-width text is saved separately so gaps are not lost by rebuilding it from mapped fields.

Fixed-width defaults add 16 Unicode code points for code and 160 for remarks, padded with spaces. `code-width` and `remarks-width` can increase those sizes, but cannot go below 16/160. Values are not silently cut off. Tell the consumer the return length is the original record length plus both appended widths.

Rename fields through `code-field` / `remarks-field`. If the input already has either name, export fails instead of overwriting customer data. Character-encoding problems, values that exceed field widths and XML root limitations also block publication and appear in the IT report. A JSON OBJECT or an XML record that is itself the document root supports at most one original record.

Return a row once. If it has several findings, choose HARD before MEDIUM before SOFT; for equal severity, sort codes alphabetically to choose consistently. IT still receives every finding. Customer remarks come from approved settings or fixed application text, never exception messages or arbitrary service remarks.

By default, return failed rows only. `include-warnings: true` also returns warning rows. `include-success: true` adds other rows with code OK after completion. PREVIEW’s OK remarks say nothing was delivered. If no rows qualify, do not create a customer file.

## Folders and handoff

Default layout:

```text
<work>/results/customer/<run-id>/
  contract-batches.results.<revision>.csv
  ready.json

<work>/results/internal/<run-id>/
  diagnostics.<revision>.json
  ready.json
```

Extensions and formats follow the input, including `.psv`, `.tsv`, `.fw`, `.xml`, `.json` and `.jsonl`. Set destination folders in Git Bash:

```bash
export CUSTOMER_RESULTS_DIR='//server/share/customer-outbox'
export INTERNAL_RESULTS_DIR='//server/share/it-outbox'
```

The folders must already be accessible. Share credentials and email destinations are outside this application. On Linux, use the share’s mounted local path.

The sending process should watch **ready.json**. Do not treat a new folder or temporary file as ready. The manifest names the published revision file and includes run ID, mode, status, completeness and `customerReady`. The application writes temporary files, renames them, then publishes the manifest. A revised result gets a new filename. Track `(runId, revision)` to avoid sending it twice. PREVIEW manifests are demonstrations, not LIVE results to send to customers.

PAUSED runs have internal diagnostics but no customer-ready manifest. Their results still need to be checked. Malformed/oversized input or an unusable profile may prevent reading all records. Keep the original in the error area. A readable prefix may be available for IT review, but never publish it as a complete customer return. Do not invent member records for unreadable lines. Intake failure without a usable profile produces an internal HARD report referencing the retained original.

H2 on disk saves originals and findings. Resume/result recreation uses those saved values, not an edited input file. Stable finding keys prevent duplicates. Saved service outcomes are reapplied after a crash, including for already acknowledged requests. Missing result files can be recreated on startup or when revisiting a completed run. Runs saved before original-record capture was added cannot gain those records afterward.

The internal report includes every HARD/MEDIUM/SOFT finding, stage/reason, approved code/remarks, request number when applicable, source record numbers, run status, input filename, completeness and counts. It does not copy tokens or raw HTTP bodies. Protect original records and identity references using the same access controls as the incoming data.

## Configure validation and contract rejection

Each profile has its own error settings, saved with the run. Without an `errors` section, parsing, lookup and JSON-building failures keep the default HARD behavior.

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

Rules check normalized source fields before lookup. `MISSING` means missing/null/blank. `EQUALS` compares with `equals-value`. These are simple POC checks, not a general rules language. Remarks are literal configured text. SOFT warns; it cannot turn a failed parser, lookup or JSON build into a success.

MEDIUM lookup handling rejects the contract identified by the configured source contract key. That includes group and member lookup failures. The key must consistently identify the same contract across members/repeated rows. Without a required contract identity, escalate to HARD. Normalize aliases first; a failed lookup cannot discover that two unresolved IDs are the same contract.

MEDIUM JSON-building handling requires `GROUPED`. Check each resolved contract before packing accepted contracts into requests. A bad date, conflicting grouped value or contract row/member-limit failure can reject that contract alone. Broken syntax, file-record overflow, missing grouping identity and infrastructure failures remain HARD. Finish preparation before sending anything.

By default, `resolved-contract-key` is `resolved.owner.contract` and `resolved-member-key` is `resolved.owner.contractMemberGuid`. Change these if another enricher uses different paths. They match service results back to source records regardless of where the outgoing JSON puts those fields.

## Optional member/contract service results

The actual service response is still undecided. The default client acknowledges by HTTP status. Use this optional **POC response format** only with a compatible test service:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.profile-id=error-demo-service-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv \
  --ingestion.mode=LIVE \
  --ingestion.delivery.response-contract=SCOPED_V1
```

Configure both lookups as HTTP with real test URLs. LIVE refuses fixtures. A configured success status must have a body such as:

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

Here, `complete: true` must guarantee that processing for this request is finished, every rejection/warning is listed, and everything else succeeded. An empty results array means all succeeded. Without that guarantee, “no error listed” cannot be treated as success. HTTP 200 alone does not establish this agreement.

Supported results are HARD/FILE, MEDIUM/CONTRACT, MEDIUM/MEMBER and SOFT at FILE/CONTRACT/MEMBER level. Identifiers must match records **in this request**, not just somewhere in the file. Validate the entire result before recording findings.

Incomplete, malformed or oversized responses, unknown codes and unknown identities pause processing. Do not guess which customer rows failed. An already acknowledged response needing investigation is not automatically POSTed again.

Map allowed codes to approved customer wording in the profile:

```yaml
errors:
  service-codes:
    MEMBER_INVALID:
      customer-code: MEMBER_REJECTED
      customer-remarks: Member details require correction.
      internal-remarks: Service rejected the member; inspect the source record references.
```

Ignore raw service remarks. A member rejection returns all that member’s submitted original rows, including payer/coverage repeats. A contract rejection returns all submitted rows for that contract. Identifying one consent or coverage needs another agreed key; array position is not enough.

Save result identities/codes with the request acknowledgement before generating files. If a later request pauses, resume skips acknowledged requests, restores their findings without duplicates and handles unresolved requests using the saved retry rules.

## Run the examples

```bash
./gradlew test bootJar
bash scripts/run-error-examples.sh
# Includes all format/mapping/error demos and verifies customer fixtures:
python scripts/verify-examples.py
```

All three PREVIEW examples use the same scattered eight-row CSV:

| Profile | What it demonstrates | Customer output |
| --- | --- | --- |
| `error-demo-hard-v1` | Stop the file on HARD validation | All eight rows; no requests prepared for delivery |
| `error-demo-medium-v1` | John’s error rejects contract A; Morgan has a warning | Four contract-A rows; other contracts continue |
| `error-demo-soft-v1` | Morgan warns; warning returns enabled | Morgan’s two rows; all contracts continue |
| `error-demo-service-v1` | Approved service codes without artificial validation failures | Compatible LIVE service tests |

Expected returns: [HARD](../examples/flexible/expected-customer-hard.csv), [MEDIUM](../examples/flexible/expected-customer-medium.csv), [SOFT](../examples/flexible/expected-customer-soft.csv). HARD intentionally exits 1; the script expects that. Tests read customer output back through all 11 layouts and check member/contract rejection and recovery.

## Group and member failures

The order is [group-context, then consent-member](group-member-lookups-v2.md). External group ID resolves to internal group/customer values before the member request. Group failure or incomplete context prevents a member call. The configured lookup severity applies.

Save the group result before resolving the member and share it across that group’s rows in the run. Customer returns keep original external group IDs; do not append internal group/customer values.
