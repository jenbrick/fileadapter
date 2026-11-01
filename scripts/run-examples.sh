#!/usr/bin/env bash
set -euo pipefail
# Run the sample inputs in PREVIEW so these commands never send consent upserts.
cd "$(dirname "$0")/.."
jar_path="${FILEADAPTER_JAR:-build/libs/fileadapter-0.1.0.jar}"
java_cmd="${JAVA:-java}"
if [[ ! -f "$jar_path" ]]; then
  echo "Build the application first: ./gradlew test bootJar" >&2
  exit 1
fi
for file in flexible-consents.csv flexible-noheader.csv \
  flexible-consents.psv flexible-noheader.psv flexible-consents.scsv \
  flexible-consents.tsv flexible-consents.fw flexible-consents.xml \
  flexible-consents.json flexible-object.json flexible-consents.jsonl; do
  echo "Running $file"
  "$java_cmd" -jar "$jar_path" --spring.profiles.active=flexible-demo \
    --ingestion.mode=PREVIEW --ingestion.file="examples/flexible/$file"
done
for profile in flexible-root-array-v1 flexible-contract-envelope-v1 \
  flexible-reshaped-v1 flexible-record-v1; do
  echo "Running output variant $profile"
  "$java_cmd" -jar "$jar_path" --spring.profiles.active=flexible-demo \
    --ingestion.mode=PREVIEW --ingestion.profile-id="$profile" \
    --ingestion.file=examples/flexible/flexible-consents.csv
done
# The same CSV can use either one-contract bodies or bounded arrays of complete contracts.
for profile in flexible-contract-single-v1 flexible-contract-array-v1; do
  echo "Running bounded contract variant $profile"
  "$java_cmd" -jar "$jar_path" --spring.profiles.active=flexible-demo \
    --ingestion.mode=PREVIEW --ingestion.profile-id="$profile" \
    --ingestion.file=examples/flexible/contract-batches.csv
done
echo "Open var-flexible-demo/preview/<run-id>/<input-file>.preview.json"

bash scripts/run-error-examples.sh
