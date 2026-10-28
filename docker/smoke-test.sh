#!/usr/bin/env bash
# End-to-end check against the running demo: triggers a demo job through the Jenkins REST API
# and verifies explainFailure() put the expected category on the build page.
#
#   docker compose up -d --build
#   ./docker/smoke-test.sh java-oom OUT_OF_MEMORY
set -euo pipefail

JOB="${1:-java-oom}"
EXPECT="${2:-OUT_OF_MEMORY}"
URL="${JENKINS_URL:-http://localhost:8080}"
AUTH="admin:${JENKINS_ADMIN_PASSWORD:-admin}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

echo "Waiting for Jenkins at $URL ..."
for _ in $(seq 1 90); do
  if curl -fs -u "$AUTH" -o /dev/null "$URL/job/demo/job/$JOB/api/json"; then break; fi
  sleep 5
done
curl -fs -u "$AUTH" -o /dev/null "$URL/job/demo/job/$JOB/api/json" || { echo "Jenkins or job not ready"; exit 1; }

crumb=$(curl -fs -u "$AUTH" -c "$JAR" "$URL/crumbIssuer/api/json" |
        python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["crumbRequestField"]+":"+d["crumb"])')

echo "Triggering demo/$JOB"
curl -fs -u "$AUTH" -b "$JAR" -H "$crumb" -X POST "$URL/job/demo/job/$JOB/build" -o /dev/null

echo "Waiting for the build to finish ..."
for _ in $(seq 1 60); do
  sleep 5
  info=$(curl -fs -u "$AUTH" "$URL/job/demo/job/$JOB/lastBuild/api/json" || true)
  [ -n "$info" ] || continue
  building=$(echo "$info" | python3 -c 'import json,sys; print(json.load(sys.stdin)["building"])')
  [ "$building" = "False" ] && break
done

echo "--- tail of console log ---"
curl -fs -u "$AUTH" "$URL/job/demo/job/$JOB/lastBuild/consoleText" | tail -25

desc=$(echo "$info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("description") or "")')
echo "--- build description: $desc"
if [[ "$desc" == *"[$EXPECT]"* ]]; then
  echo "PASS: demo/$JOB explained as $EXPECT"
else
  echo "FAIL: expected [$EXPECT] in the build description"
  exit 1
fi
