#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ui_root="$(cd "$script_dir/.." && pwd)"
repo_root="$(cd "$ui_root/.." && pwd)"
run_root="${RUNNER_TEMP:-$repo_root/.n08-ci-${GITHUB_RUN_ID:-manual}-${GITHUB_RUN_ATTEMPT:-1}}"
dist_root="${N08_DIST_DIR:-$ui_root/w8-dist-20260907-a}"
report_path="${N08_REPORT_PATH:-$repo_root/docs/performance-data/n08-browser-$(date -u +%Y%m%dT%H%M%SZ).json}"
server_log="$run_root/n08-browser-server.log"
session_name="n08-slow-${RANDOM}"

mkdir -p "$run_root"

(
  cd "$ui_root"
  node scripts/w7-browser-fixture.mjs \
    --root "$dist_root" \
    --output "$report_path" \
    --expected slow-polling
) >"$server_log" 2>&1 &
server_pid=$!

cleanup() {
  if kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

base_url=""
for attempt in $(seq 1 60); do
  if [[ -f "$server_log" ]]; then
    base_url="$(sed -n 's/^W7_BROWSER_BASE_URL=//p' "$server_log" | head -n 1)"
  fi
  if [[ -n "$base_url" ]]; then
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    sed -n '1,160p' "$server_log"
    exit 1
  fi
  sleep 1
done

if [[ -z "$base_url" ]]; then
  sed -n '1,160p' "$server_log"
  exit 1
fi

pwcli=(npx --yes --package @playwright/cli playwright-cli)
"${pwcli[@]}" install-browser chromium
"${pwcli[@]}" --session "$session_name" open "$base_url/__w7/auth/?w7Scenario=slow-polling#/home"
"${pwcli[@]}" --session "$session_name" run-code --filename "$script_dir/w7-playwright-capture.mjs"
"${pwcli[@]}" --session "$session_name" close || true

for attempt in $(seq 1 30); do
  if [[ -f "$report_path" ]]; then
    break
  fi
  sleep 1
done

if [[ ! -f "$report_path" ]]; then
  sed -n '1,200p' "$server_log"
  exit 1
fi

node --input-type=module - "$report_path" <<'NODE'
import {readFileSync} from 'node:fs'

const reportPath = process.argv[2]
const report = JSON.parse(readFileSync(reportPath, 'utf8'))
const scenario = report.scenarios?.['slow-polling']
if (!scenario) throw new Error('missing slow-polling browser scenario')
if (scenario.pageDwellMs < 30_000) {
  throw new Error(`page dwell was too short: ${scenario.pageDwellMs}ms`)
}
if (scenario.pageErrors?.length || scenario.consoleErrors?.length
    || scenario.chunk404s?.length || scenario.mediaErrors?.length) {
  throw new Error(`browser errors: ${JSON.stringify({
    pageErrors: scenario.pageErrors,
    consoleErrors: scenario.consoleErrors,
    chunk404s: scenario.chunk404s,
    mediaErrors: scenario.mediaErrors
  })}`)
}
const polling = scenario.polling
if (polling?.maxInFlight !== 1 || polling?.totalRequests < 2) {
  throw new Error(`invalid polling concurrency: ${JSON.stringify(polling)}`)
}
if (polling.active !== 0 || polling.trace.some(item => !item.endedAtMs || item.durationMs < 7_500)) {
  throw new Error(`incomplete or short polling trace: ${JSON.stringify(polling)}`)
}
if (scenario.hiddenState?.polling?.totalRequests !== 1
    || scenario.hiddenState.polling.maxInFlight !== 1) {
  throw new Error(`hidden polling did not pause: ${JSON.stringify(scenario.hiddenState)}`)
}
if (!scenario.manualRefreshClicked || !scenario.visibleRecoveryObserved) {
  throw new Error(`recovery interaction missing: ${JSON.stringify({
    manualRefreshClicked: scenario.manualRefreshClicked,
    visibleRecoveryObserved: scenario.visibleRecoveryObserved
  })}`)
}
console.log(`N08 browser slow polling passed: ${reportPath}`)
NODE
