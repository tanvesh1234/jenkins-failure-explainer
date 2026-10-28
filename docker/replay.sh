#!/usr/bin/env bash
# Replays the command output of a recorded failure log inside a real Jenkins sh step,
# then exits with the same code the original build did. Lets the demo show realistic
# Maven / npm / pytest / Gradle failures without installing those toolchains.
#
#   replay.sh maven-compile-error
set -euo pipefail

log="/opt/demo/logs/${1:?usage: replay.sh <scenario>}.log"
[ -f "$log" ] || { echo "No such scenario: $1" >&2; exit 2; }

# Print the lines between the build stage's "[Pipeline] sh" and the next "[Pipeline] }".
awk '
  /\[Pipeline\] sh$/ && !started && seen_stage { started = 1; next }
  /\[Pipeline\] \{ \(/ && !/\(Checkout\)/ { seen_stage = 1 }
  started && /^\[Pipeline\] \}/ { exit }
  started { print; fflush(); system("sleep 0.05") }
' "$log"

code=$(grep -oE 'script returned exit code [0-9]+' "$log" | grep -oE '[0-9]+$' | tail -1)
exit "${code:-1}"
