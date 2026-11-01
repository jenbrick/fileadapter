# Run the FileAdapter POC in Git Bash

These commands are for **Git Bash on Windows**, run from the repository root. They run the current POC, which demonstrates more formats and options than the proposed CSV-only January release.

## 1. Open the repository

If you already cloned the repository, open its folder:

```bash
cd /c/work/file-adapter-poc
pwd
ls
```

Replace the path with your actual folder. Git Bash writes `C:\work\file-adapter-poc` as `/c/work/file-adapter-poc`. Put paths containing spaces in quotes.

For a new external clone:

```bash
mkdir -p /c/work
cd /c/work
git clone https://github.com/jenbrick/fileadapter.git
cd fileadapter
```

For the company copy, use its internal clone URL instead. The folder name may be `file-adapter-poc`; the JAR name below is still `fileadapter-0.1.0.jar`.

To update an existing clone, first check for local changes:

```bash
git status
git pull --ff-only
```

If Git reports local changes or diverged branches, resolve those before continuing. Do not discard changes just to run the demo.

## 2. Check Java

Install a company-approved **JDK 17**, then check:

```bash
java -version
javac -version
```

If Java is missing or the wrong installation is selected, set the installed JDK path for this Git Bash window. Replace the example path with your actual JDK folder:

```bash
export JAVA_HOME='/c/Program Files/Java/jdk-17'
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
```

`JAVA_HOME` points to the JDK folder, not its `bin` folder. These exports apply only to this terminal. For a permanent setting, use Windows environment variables and reopen Git Bash.

## 3. Build and run the tests

```bash
./gradlew test bootJar
```

The first build downloads Gradle and dependencies and needs access through the company’s approved network/proxy setup. The build uses Java 17 and can provision that toolchain when approved network access allows it.

The executable JAR is:

```text
build/libs/fileadapter-0.1.0.jar
```

Useful build checks:

```bash
./gradlew --version
./gradlew -q javaToolchains
```

You do not need to build before every example. Rebuild after changing Java code or packaged configuration.

## 4. Set up an isolated preview session

Use these settings in the same Git Bash window before the examples:

```bash
export GROUP_LOOKUP_MODE=FIXTURE
export FLEXIBLE_LOOKUP_MODE=FIXTURE
unset CUSTOMER_RESULTS_DIR INTERNAL_RESULTS_DIR
unset CONTRACTS_PER_REQUEST CONTRACT_MAX_MEMBERS CONTRACT_MAX_ROWS
```

This selects the packaged synthetic group/member lookup data, keeps result files in the local demo folders and restores the default contract limits. The example commands below explicitly select PREVIEW, so they write prospective requests without sending consent upserts.

## 5. Run the basic CSV example

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.file=examples/flexible/flexible-consents.csv
```

This reads three source records, resolves group and member information using fixtures, and builds consent JSON. It processes the file and exits.

The output is under:

```text
var-flexible-demo/preview/<run-id>/
```

Inside that folder:

- `flexible-consents.csv.preview.json` contains run/source information and the generated request bodies.
- `requests.jsonl` contains one complete prospective HTTP request body per line.

Find the generated files:

```bash
find var-flexible-demo/preview -type f
explorer.exe var-flexible-demo
```

Compare the basic request body with `examples/flexible/expected-envelope.json`. The preview file wraps the body with metadata; it will not be textually identical to that fixture.

## 6. Run both contract JSON examples

The input below contains eight rows, three contracts and five members. Rows for the same contract appear in different places in the file. Some members have multiple payer consent/coverage records.

### One complete contract per request

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

Expected: **three request bodies**, each containing one complete contract.

### Several complete contracts per request, with a limit

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

Expected: **two request bodies**. The first is an array containing two contracts; the second contains one contract.

| Setting | Meaning |
| --- | --- |
| `CONTRACTS_PER_REQUEST` | Maximum contracts per array request; the single-contract profile always sends one |
| `CONTRACT_MAX_MEMBERS` | Maximum distinct members in one contract |
| `CONTRACT_MAX_ROWS` | Maximum input rows contributing to one contract |

The member limit must be no greater than the row limit, and the row limit must be no greater than the profile’s file-record limit (1,000 for these demos). A contract is not silently split to fit. These settings limit counts; the POC does not enforce a serialized JSON byte-size limit.

Look for `contract-batches.csv.preview.json` and `requests.jsonl` in the run folders. Compare the request lists with:

- `examples/flexible/expected-contract-single-requests.json`
- `examples/flexible/expected-contract-array-requests.json`

Each expected fixture is a list of all request bodies. In the actual single-contract example, each individual HTTP body is an object. In the array example, each body is an array. Members are identified within consent entries by `consentOwner`; these candidate JSON shapes do not have a separate `members` array.

Restore defaults before the remaining examples:

```bash
unset CONTRACTS_PER_REQUEST CONTRACT_MAX_MEMBERS CONTRACT_MAX_ROWS
```

## 7. Run all packaged examples

After building:

```bash
bash scripts/run-examples.sh
```

This runs all 11 input layouts, the alternate output shapes, both contract profiles and the three error examples. It explicitly uses PREVIEW.

| Input format | Example filename in `examples/flexible/` |
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
| Single JSON object | `flexible-object.json` |
| JSON Lines | `flexible-consents.jsonl` |

To run just one, substitute its filename:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.file=examples/flexible/flexible-consents.xml
```

The packaged filenames select their configured layouts automatically. A differently named file may need an explicit `--ingestion.profile-id`; its layout and lookup data must match the selected profile.

## 8. Run just the error examples

```bash
bash scripts/run-error-examples.sh
```

| Example | Expected behavior |
| --- | --- |
| HARD | Rejects the full file; returns all eight original rows with code/remarks |
| MEDIUM | Rejects contract A; returns its four original rows; other contracts proceed in preview |
| SOFT | Records a warning and continues; this demo includes the two warning rows in the customer file |

The HARD example intentionally exits with code 1. The runner expects that result and continues.

Find the files:

```bash
find var-flexible-demo/results/customer -type f
find var-flexible-demo/results/internal -type f
```

The customer file preserves original fields and adds `RESULT_CODE` and `RESULT_REMARKS`. The internal JSON report contains the detailed findings.

Each run folder has a `ready.json` manifest naming the published revision file. No customer file is required when no rows qualify. An unresolved run does not publish a final customer-ready result. An outside process handles customer transmission and IT email; PREVIEW results are demo artifacts.

## 9. Automatically compare outputs with expected examples

With Python 3 installed:

```bash
python --version
python scripts/verify-examples.py
```

If Windows provides Python through the launcher instead:

```bash
py -3 scripts/verify-examples.py
```

The verifier runs **20 packaged cases**, uses temporary work directories and compares generated requests/customer returns with expected files. A successful run ends with:

```text
Verified 20 packaged example runs.
```

Python is optional for running the application itself.

## 10. Repeat a demo using a separate work folder

Identical input and configuration can reuse an already completed run. To demonstrate a fresh run without removing existing history, choose a new folder:

```bash
java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.work-directory=./var-demo-fresh-01 \
  --ingestion.profile-id=flexible-contract-single-v1 \
  --ingestion.file=examples/flexible/contract-batches.csv
```

Outputs are now below `var-demo-fresh-01`. Use a different name next time if you need another fresh history. Keep the same work folder when recovering an existing run; it contains the saved state and request bodies. Run one application instance per work folder.

## 11. Optional: watch an input folder

Start the poller without `--ingestion.file`:

```bash
mkdir -p var-demo-poll/incoming

java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.mode=PREVIEW \
  --ingestion.work-directory=./var-demo-poll \
  --ingestion.input-directory=./var-demo-poll/incoming \
  --ingestion.poll-enabled=true
```

Leave that terminal running. In a second Git Bash terminal, open the same repository folder and copy the finished file, then create its ready marker:

```bash
cp examples/flexible/flexible-consents.csv var-demo-poll/incoming/flexible-consents.csv
touch var-demo-poll/incoming/flexible-consents.csv.ready
```

The marker means the file is finished and closed. The poller uses the configured age/poll intervals, so allow a few seconds. Stop it with **Ctrl+C**. Unlike polling, the one-file examples do not require a ready marker.

For a LAN share, replace the input path with an accessible mounted path such as `//server/share/incoming`. The application does not mount the share or manage its credentials.

## 12. Optional: use real lookup test services

The group call happens first. Its internal group number and customer ID are included in the member-only demographics request.

In a separate Git Bash session, set the actual compatible test URLs:

```bash
export GROUP_LOOKUP_MODE=HTTP
export GROUP_LOOKUP_URL='http://localhost:8083/groups/resolve'
export FLEXIBLE_LOOKUP_MODE=HTTP
export CONSENT_LOOKUP_URL='http://localhost:8082/members/resolve'
```

These URLs are examples, not servers started by the JAR. The company services may need adapters for the POC request/response formats; see [group/member lookup details](group-member-lookups.md).

If tokens are required, enter them without putting their values in the command history:

```bash
read -r -s -p 'Group lookup token: ' GROUP_LOOKUP_BEARER_TOKEN
echo
export GROUP_LOOKUP_BEARER_TOKEN
read -r -s -p 'Member lookup token: ' CONSENT_LOOKUP_BEARER_TOKEN
echo
export CONSENT_LOOKUP_BEARER_TOKEN
```

Run the same one-file command with `--ingestion.mode=PREVIEW` to exercise HTTP lookups and inspect the JSON without sending an upsert.

### Send to an agreed test upsert endpoint

Once the lookup services, JSON shape and receiving-service behavior agree:

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

**LIVE sends real requests to the configured test service.** Both lookup modes must be HTTP; LIVE refuses fixtures. Token prompts can be skipped for test services that do not require authentication. Do not turn on replay safety unless the receiving service guarantees duplicate-safe retries.

For the opt-in POC member-failure response format, see [service-result examples](error-handling.md). It requires a compatible service and additional settings; a normal HTTP response does not automatically provide member-level results.

### Resume a saved run

Use the original work directory and its actual run ID:

```bash
read -r -p 'Saved run ID: ' run_id

java -jar build/libs/fileadapter-0.1.0.jar \
  --spring.profiles.active=flexible-demo \
  --ingestion.work-directory=./var-live-test-01 \
  --ingestion.resume="$run_id"
```

Resume uses the saved mode, bodies and delivery settings. Keep the required service credentials available in the environment. Previously acknowledged requests are skipped. An uncertain send still needs reconciliation or an agreed safe replay policy; resume does not make an unsafe retry safe. Do not supply `--ingestion.file` and `--ingestion.resume` together.

## 13. Common problems

| Problem | What to check |
| --- | --- |
| `java: command not found` | Set `JAVA_HOME` and `PATH`, then check `java -version` |
| Missing Java 17 toolchain | Install/select JDK 17; inspect `./gradlew -q javaToolchains` |
| `./gradlew: Permission denied` | Run `bash ./gradlew test bootJar` |
| `Unable to access jarfile` | Run from the repository root and build the JAR first |
| Dependency download fails | Check the approved company proxy/repository setup; do not disable certificate checking |
| No matching profile | Use the packaged filename or select the matching profile explicitly |
| Member/group lookup fails with fixtures | Use packaged synthetic records, or configure real compatible HTTP lookup services |
| No new output when rerunning | An identical completed run may be reused; use a new PREVIEW work folder if desired |
| Customer output is absent | Check whether any rows qualify, then inspect internal diagnostics and run status |
| Application stays running | Supply `--ingestion.file` for a one-file run; no file/resume leaves the application running |
| H2/work-folder lock | Stop the other instance using that folder, or use a different folder for an independent demo |

In Git Bash, a continuation backslash must be the last character on its line. Do not put spaces after it. Use `./gradlew` in Git Bash and `gradlew.bat` in Windows Command Prompt.

## More detail

- [Input formats and expected output files](formats.md)
- [Contract limits and both JSON candidates](contract-batching.md)
- [Error files and service-result handling](error-handling.md)
- [Group and member lookups](group-member-lookups.md)
- [CSV walkthrough through the code](csv-debug-walkthrough.md)
- [January release class and sequence diagrams](january-release-diagrams.md)
