#!/usr/bin/env python3
"""Run the built JAR against every example and compare semantic JSON bodies."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples/flexible"
CASES = [
    ("flexible-consents.csv", None, "expected-envelope.json"),
    ("flexible-noheader.csv", None, "expected-envelope.json"),
    ("flexible-consents.psv", None, "expected-envelope.json"),
    ("flexible-noheader.psv", None, "expected-envelope.json"),
    ("flexible-consents.scsv", None, "expected-envelope.json"),
    ("flexible-consents.tsv", None, "expected-envelope.json"),
    ("flexible-consents.fw", None, "expected-envelope.json"),
    ("flexible-consents.xml", None, "expected-envelope.json"),
    ("flexible-consents.json", None, "expected-envelope.json"),
    ("flexible-object.json", None, "expected-object-envelope.json"),
    ("flexible-consents.jsonl", None, "expected-envelope.json"),
    ("flexible-consents.csv", "flexible-root-array-v1", "expected-root-array.json"),
    ("flexible-consents.csv", "flexible-contract-envelope-v1", "expected-contract-envelope.json"),
    ("flexible-consents.csv", "flexible-reshaped-v1", "expected-reshaped.json"),
    ("flexible-consents.csv", "flexible-record-v1", None),
    ("contract-batches.csv", "flexible-contract-single-v1", "expected-contract-single-requests.json"),
    ("contract-batches.csv", "flexible-contract-array-v1", "expected-contract-array-requests.json"),
]


def main():
    jar = Path(os.environ.get("FILEADAPTER_JAR", ROOT / "build/libs/fileadapter-0.1.0.jar")).resolve()
    if not jar.is_file():
        raise SystemExit("Build the application first: ./gradlew test bootJar")
    for filename, profile, expected in CASES:
        with tempfile.TemporaryDirectory(prefix="fileadapter-example-") as directory:
            work = Path(directory)
            command = [os.environ.get("JAVA", "java"), "-jar", str(jar),
                       "--spring.profiles.active=flexible-demo", "--ingestion.mode=PREVIEW",
                       "--ingestion.work-directory=" + str(work),
                       "--ingestion.file=" + str(EXAMPLES / filename)]
            if profile:
                command.append("--ingestion.profile-id=" + profile)
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=90)
            if result.returncode:
                raise RuntimeError(f"{filename}/{profile}: application failed\n{result.stdout}\n{result.stderr}")
            previews = list((work / "preview").glob("*/*.preview.json"))
            if len(previews) != 1:
                raise AssertionError(f"Expected one preview for {filename}/{profile}: {previews}")
            preview = json.loads(previews[0].read_text(encoding="utf-8"))
            actual = preview["requests"]
            if expected:
                fixture = json.loads((EXAMPLES / expected).read_text(encoding="utf-8"))
                wanted = fixture if expected.endswith("-requests.json") else [fixture]
            else:
                rows = json.loads((EXAMPLES / "flexible-consents.json").read_text(encoding="utf-8"))
                wanted = [{"person": {"firstName": row["FIRST_NAME"].strip(),
                                      "lastName": row["LAST_NAME"].strip()}} for row in rows]
            if actual != wanted:
                raise AssertionError(f"JSON mismatch: {filename}/{profile}")
            lines = [json.loads(line) for line in (previews[0].parent / "requests.jsonl").read_text(encoding="utf-8").splitlines()]
            if lines != wanted or preview["source"]["fileName"] != filename:
                raise AssertionError(f"JSONL or source metadata mismatch: {filename}/{profile}")
            print("PASS", filename, profile or "automatic routing", flush=True)
    verify_errors(jar)
    print(f"Verified {len(CASES) + 3} packaged example runs.")


def verify_errors(jar):
    import csv
    for flavor in ("hard", "medium", "soft"):
        with tempfile.TemporaryDirectory(prefix="fileadapter-errors-") as directory:
            work = Path(directory)
            command = [os.environ.get("JAVA", "java"), "-jar", str(jar),
                       "--spring.profiles.active=flexible-demo", "--ingestion.mode=PREVIEW",
                       "--ingestion.work-directory=" + str(work),
                       "--ingestion.profile-id=error-demo-" + flavor + "-v1",
                       "--ingestion.file=" + str(EXAMPLES / "contract-batches.csv")]
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=90)
            expected_exit = 1 if flavor == "hard" else 0
            if result.returncode != expected_exit:
                raise AssertionError(f"Unexpected exit for {flavor}: {result.stdout} {result.stderr}")
            ready = list((work / "results/customer").glob("*/ready.json"))
            if len(ready) != 1:
                raise AssertionError(f"Missing customer-ready result for {flavor}")
            manifest = json.loads(ready[0].read_text())
            with (ready[0].parent / manifest["customerFile"]).open(newline="") as actual_file:
                actual = list(csv.DictReader(actual_file))
            with (EXAMPLES / f"expected-customer-{flavor}.csv").open(newline="") as expected_file:
                expected = list(csv.DictReader(expected_file))
            if actual != expected:
                raise AssertionError(f"Customer original-record return mismatch: {flavor}")
            internal = list((work / "results/internal").glob("*/ready.json"))
            if len(internal) != 1:
                raise AssertionError("Missing internal result manifest")
            internal_manifest = json.loads(internal[0].read_text())
            report = json.loads((internal[0].parent / internal_manifest["internalFile"]).read_text())
            if not report["completeSource"] or not report["findings"]:
                raise AssertionError("Missing complete diagnostic findings")
            expected_status = {"hard": "FAILED", "medium": "PREVIEWED_WITH_ERRORS", "soft": "PREVIEWED"}[flavor]
            if report["status"] != expected_status:
                raise AssertionError("Wrong severity behavior")
            print("PASS", "customer/internal results", flavor, flush=True)


if __name__ == "__main__":
    main()
