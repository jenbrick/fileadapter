# Run the POC from Git Bash

Use **Git Bash on Windows** and run these commands from the repository’s top folder. This guide runs the current POC. January’s proposed CSV-only release is a smaller scope.

## 1. Open your copy of the code

If already cloned:

```bash
cd /c/work/file-adapter-poc
pwd
ls
```

Use your own folder path. In Git Bash, `C:\work\file-adapter-poc` becomes `/c/work/file-adapter-poc`. Quote paths with spaces.

For a new external clone:

```bash
mkdir -p /c/work
cd /c/work
git clone https://github.com/jenbrick/fileadapter.git
cd fileadapter
```

Use the internal URL for the company repository. Your folder can be called `file-adapter-poc`; the JAR is still named `fileadapter-0.1.0.jar`.

Before updating an existing copy:

```bash
git status
git pull --ff-only
```

Resolve any local changes or diverged branches first. Keep your changes rather than discarding them to run the demo.

## 2. Check Java

Use a company-approved **JDK 17**:

```bash
java -version
javac -version
```

If Java is missing or the wrong one is selected, use your actual installed JDK path here:

```bash
export JAVA_HOME='/c/Program Files/Java/jdk-17'
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

`JAVA_HOME` is the JDK folder, not `bin`. These settings last for this Git Bash window. For permanent Windows settings, update environment variables and reopen Git Bash.

## 3. Build the application and run tests

```bash
./gradlew test bootJar
```

The first build downloads Gradle/dependencies through the approved network/proxy setup. The project uses Java 17 and can download that toolchain if network access allows it.

The runnable JAR is:

```text
build/libs/fileadapter-0.1.0.jar
```

To inspect Gradle and its Java installations:

```bash
./gradlew --version
./gradlew -q javaToolchains
```

Build again after Java or packaged-configuration changes. You do not need to build before every example.

## 4. Use the local preview settings

In the same Git Bash window:

```bash
export GROUP_LOOKUP_MODE=FIXTURE
export FLEXIBLE_LOOKUP_MODE=FIXTURE
unset CUSTOMER_RESULTS_DIR INTERNAL_RESULTS_DIR
unset CONTRACTS_PER_REQUEST CONTRACT_MAX_MEMBERS CONTRACT_MAX_ROWS
```

This selects packaged synthetic lookups, local result folders and default contract limits. The following demos explicitly use PREVIEW: they build request bodies without sending consent updates.

## 5. Run the basic CSV

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

The application reads three records, uses fixture group/member results, builds consent JSON and exits.

Output goes under:

```text
var-flexible-demo/preview/<run-id>/
```

`flexible-consents.csv.preview.json` includes run/source details and request bodies. `requests.jsonl` has one complete prospective body per line.

Find/open the output folder:

```bash
find var-flexible-demo/preview -type f
explorer.exe var-flexible-demo
```

Compare the body with `examples/flexible/expected-envelope.json`. The preview has extra metadata, so the entire file is not an exact text match.

## 6. Try both contract JSON formats

The input has eight rows, three contracts and five members. Contract rows are scattered through the file. Some members have several payer consents/coverages.

### One complete contract per request

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

Expect **three bodies**, each an object containing one complete contract.

### Several complete contracts per request

```bash
export CONTRACTS_PER_REQUEST=2
export CONTRACT_MAX_MEMBERS=25
export CONTRACT_MAX_ROWS=100

java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.profile-id=flexible-contract-array-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

Expect **two bodies**: an array with two contracts, then an array with one.

| Setting | What it limits |
| --- | --- |
| `CONTRACTS_PER_REQUEST` | Contracts in an array request; single mode always uses one |
| `CONTRACT_MAX_MEMBERS` | Distinct members in one contract |
| `CONTRACT_MAX_ROWS` | Source rows for one contract |

Member limit must not exceed row limit, and row limit must not exceed the file-record limit, 1,000 here. A contract is never quietly split to fit. These settings count items; they do not check JSON byte size.

Find `contract-batches.csv.preview.json` and `requests.jsonl` in each run folder. Compare the request lists with:

- `examples/flexible/expected-contract-single-requests.json`
- `examples/flexible/expected-contract-array-requests.json`

Expected files list all bodies. Each actual single-contract body is an object; each multi-contract body is an array. `consentOwner` identifies members inside consent entries. There is no separate `members` array in these candidate formats.

Restore default limits:

```bash
unset CONTRACTS_PER_REQUEST CONTRACT_MAX_MEMBERS CONTRACT_MAX_ROWS
```

## 7. Run all examples

After building:

```bash
bash scripts/run-examples.sh
```

This runs all 11 layouts, alternate JSON, both contract profiles and three error demos in PREVIEW.

| Format | File under `examples/flexible/` |
| --- | --- |
| CSV with header | `flexible-consents.csv` |
| CSV without header | `flexible-noheader.csv` |
| Pipe with header | `flexible-consents.psv` |
| Pipe without header | `flexible-noheader.psv` |
| Semicolon | `flexible-consents.scsv` |
| Tab | `flexible-consents.tsv` |
| Fixed width | `flexible-consents.fw` |
| XML | `flexible-consents.xml` |
| JSON array | `flexible-consents.json` |
| JSON object | `flexible-object.json` |
| JSON Lines | `flexible-consents.jsonl` |

To run one format, change the filename:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.file=examples/flexible/flexible-consents.xml
```

Packaged filenames select layouts automatically. A different name may need `--ingestion.profile-id`. Input layout and lookup data must match that profile.

## 8. Run only the error demos

```bash
bash scripts/run-error-examples.sh
```

| Demo | What to expect |
| --- | --- |
| HARD | File rejected; all eight original rows returned with code/remarks |
| MEDIUM | Contract A rejected; its four rows returned; other contracts continue in PREVIEW |
| SOFT | Processing continues; this profile returns the two warning rows |

HARD intentionally exits 1. The script expects it and continues.

Find result files:

```bash
find var-flexible-demo/results/customer -type f
find var-flexible-demo/results/internal -type f
```

Customer rows retain original fields and add `RESULT_CODE` / `RESULT_REMARKS`. IT gets detailed JSON findings.

`ready.json` identifies each published revision. No customer file is needed when no rows qualify, and unresolved runs do not publish final customer-ready results. Another process sends files/email. PREVIEW results are examples, not real customer-delivery results.

## 9. Compare generated output automatically

With Python 3:

```bash
python --version
python scripts/verify-examples.py
```

Or with the Windows Python launcher:

```bash
py -3 scripts/verify-examples.py
```

The verifier uses temporary work folders and compares requests/customer returns with expected files across **20 cases**. Success ends with:

```text
Verified 20 packaged example runs.
```

You do not need Python to run the application.

## 10. Start a fresh demo without deleting history

Identical content/settings may reuse a completed run. Choose a new work folder when you want to start from scratch:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.work-directory=./var-demo-fresh-01 \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

Output is now under `var-demo-fresh-01`. Use another name for another fresh run. For recovery, keep the original work folder: it has saved state and requests. Use one application instance per folder.

## 11. Optional: watch a folder

Start without `--ingestion.file`:

```bash
mkdir -p var-demo-poll/incoming

java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.work-directory=./var-demo-poll \
  --ingestion.input-directory=./var-demo-poll/incoming \
  --ingestion.poll-enabled=true
```

Leave it running. In another Git Bash window, open the same repository and copy the completed file before creating the marker:

```bash
cp examples/flexible/flexible-consents.csv var-demo-poll/incoming/flexible-consents.csv
touch var-demo-poll/incoming/flexible-consents.csv.ready
```

The marker says writing is finished and the file is closed. Allow a few seconds for age/poll checks. Stop with **Ctrl+C**. One-file commands do not need markers.

For LAN input, use an already accessible mounted path, for example `//server/share/incoming`. The application does not mount the share or handle credentials.

## 12. Optional: call lookup test services

Group lookup happens first. Member lookup then gets demographics/identifiers plus internal group number/customer ID.

In a separate Git Bash session, set compatible test URLs:

```bash
export GROUP_LOOKUP_MODE=HTTP
export GROUP_LOOKUP_URL='http://localhost:8083/groups/resolve'
export FLEXIBLE_LOOKUP_MODE=HTTP
export CONSENT_LOOKUP_URL='http://localhost:8082/members/resolve'
```

These are example addresses. The JAR does not start those servers. Company services may need adapters for the POC formats; see [lookup details](group-member-lookups-v2.md).

If authentication is required, prompt for tokens without putting their values in command history:

```bash
read -r -s -p 'Group lookup token: ' GROUP_LOOKUP_BEARER_TOKEN
echo
export GROUP_LOOKUP_BEARER_TOKEN
read -r -s -p 'Member lookup token: ' CONSENT_LOOKUP_BEARER_TOKEN
echo
export CONSENT_LOOKUP_BEARER_TOKEN
```

Use the one-file command with `--ingestion.mode=PREVIEW` to exercise HTTP lookups and inspect JSON without sending consent updates.

### Send to an agreed test upsert service

Once lookups, JSON and receiving-service behavior agree:

```bash
export UPSERT_URL='http://localhost:8081/consents/upsert'
read -r -s -p 'Upsert token: ' UPSERT_BEARER_TOKEN
echo
export UPSERT_BEARER_TOKEN

java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=LIVE \
  --ingestion.work-directory=./var-live-test-01 \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

**LIVE sends updates to the configured service.** Both lookup modes must be HTTP; fixtures are refused. Skip token prompts if your test services need no tokens. Enable replay safety only when the service guarantees duplicate-safe retry.

The optional member-failure response format needs a compatible service and extra settings. See [service-result handling](error-handling-v2.md). A normal HTTP response does not automatically identify failed members.

### Resume an existing run

Use its original work folder and actual run ID:

```bash
read -r -p 'Saved run ID: ' run_id

java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.work-directory=./var-live-test-01 \
  --ingestion.resume="$run_id"
```

Resume uses the saved mode, bodies and delivery settings. Keep required credentials available. It skips acknowledged requests. An uncertain send still needs investigation or an agreed safe replay policy; resume does not make it safe by itself. Supply file or resume, not both.

## 13. Troubleshooting

| Problem | Check |
| --- | --- |
| `java: command not found` | Set `JAVA_HOME` / `PATH`; run `java -version` |
| Java 17 toolchain missing | Install/select JDK 17; use `./gradlew -q javaToolchains` |
| `./gradlew: Permission denied` | Try `bash ./gradlew test bootJar` |
| `Unable to access jarfile` | Open the repository root and build first |
| Dependency download fails | Check approved proxy/repository settings; keep certificate checking enabled |
| No matching profile | Use the example filename or explicitly select the correct profile |
| Fixture lookup fails | Use the packaged records or compatible HTTP services |
| No new output | A completed identical run may be reused; choose a fresh PREVIEW work folder |
| No customer file | Check whether rows qualify and inspect diagnostics/status |
| Application keeps running | Supply `--ingestion.file` for a one-file run |
| H2/work-folder lock | Stop the other instance or use a different folder for an independent demo |

A continuation backslash must be the last character on its line, with no trailing spaces. Git Bash uses `./gradlew`; Command Prompt uses `gradlew.bat`.

## Related guides

- [Formats and expected files](formats-v2.md)
- [Both contract JSON formats and limits](contract-batching-v2.md)
- [Error files and service results](error-handling-v2.md)
- [Group/member lookups](group-member-lookups-v2.md)
- [CSV code walkthrough](csv-debug-walkthrough-v2.md)
- [January diagrams](january-release-diagrams-v2.md)
