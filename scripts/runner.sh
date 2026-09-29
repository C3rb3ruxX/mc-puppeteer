#!/usr/bin/env bash
#
# Launches N Minecraft dev clients (offline / no premium) in the background,
# one `./gradlew runClient` process per instance.
#
# Usage: ./scrips/runner.sh [instances]   (default: 3)

set -euo pipefail

COUNT="${1:-3}"

# Only accept a positive integer, otherwise the for-loop below never runs.
if ! [[ "$COUNT" =~ ^[1-9][0-9]*$ ]]; then
	echo "error: instance count must be a positive integer (got '$COUNT')" >&2
	echo "usage: $(basename "$0") [instances]" >&2
	exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/logs/instances"
mkdir -p "$LOG_DIR"

pids=()

cleanup() {
	echo
	echo "stopping: ${pids[*]:-none}"
	kill "${pids[@]:-}" 2>/dev/null || true
	wait 2>/dev/null || true
	exit 0
}
trap cleanup INT TERM

for ((i = 1; i <= COUNT; i++)); do
	name="Player$i"
	echo "starting $i/$COUNT as $name ..."
	./gradlew runClient --args="--username $name" >"$LOG_DIR/$name.log" 2>&1 &
	pids+=("$!")
done

echo
echo "launched $COUNT client(s)"
echo "  pids: ${pids[*]}"
echo "  logs: $LOG_DIR"
echo "  stop: kill ${pids[*]}"

wait
