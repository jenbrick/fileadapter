#!/usr/bin/env bash
set -euo pipefail
# Exercise the three severity outcomes and leave their customer/IT files available to inspect.
cd "$(dirname "$0")/.."
jar_path="${FILEADAPTER_JAR:-build/libs/fileadapter-0.1.0.jar}"
java_cmd="${JAVA:-java}"
for flavor in medium soft hard; do
  echo "Running error-demo-$flavor-v1 (PREVIEW)"
  result=0
  "$java_cmd" -jar "$jar_path" --spring.profiles.active=flexible-demo \
    --ingestion.mode=PREVIEW --ingestion.profile-id="error-demo-$flavor-v1" \
    --ingestion.file=examples/flexible/contract-batches.csv || result=$?
  # Treat the intentional hard rejection as an expected result, not a broken demo.
  if [[ "$flavor" == hard ]]; then
    [[ "$result" == 1 ]] || { echo "Expected hard demo to exit 1" >&2; exit 1; }
  else
    [[ "$result" == 0 ]] || exit "$result"
  fi
done
echo "Customer: var-flexible-demo/results/customer/<run-id>/ready.json"
echo "Internal: var-flexible-demo/results/internal/<run-id>/ready.json"
