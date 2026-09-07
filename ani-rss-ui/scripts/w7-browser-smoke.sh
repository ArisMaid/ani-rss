#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ui_root="$(cd "$script_dir/.." && pwd)"
repo_root="$(cd "$ui_root/.." && pwd)"
run_root="${RUNNER_TEMP:-$repo_root/.w7-ci-${GITHUB_RUN_ID:-manual}-${GITHUB_RUN_ATTEMPT:-1}}"
dist_root="${W7_DIST_DIR:-$run_root/w7-dist}"
report_path="${W7_REPORT_PATH:-$run_root/w7-browser-report.json}"
server_log="$run_root/w7-browser-server.log"

mkdir -p "$run_root"

expected="login-cold,home-cold,subscriptions-cold"
(
  cd "$ui_root"
  node scripts/w7-browser-fixture.mjs \
    --root "$dist_root" \
    --output "$report_path" \
    --expected "$expected"
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

"${pwcli[@]}" --session w7-login open "$base_url/__w7/unauth/?w7Scenario=login-cold"
"${pwcli[@]}" --session w7-login run-code --filename "$script_dir/w7-playwright-capture.mjs"
"${pwcli[@]}" --session w7-login close || true

"${pwcli[@]}" --session w7-home open "$base_url/__w7/auth/?w7Scenario=home-cold#/home"
"${pwcli[@]}" --session w7-home run-code --filename "$script_dir/w7-playwright-capture.mjs"
"${pwcli[@]}" --session w7-home close || true

"${pwcli[@]}" --session w7-subscriptions open "$base_url/__w7/auth/?w7Scenario=subscriptions-cold#/subscriptions"
"${pwcli[@]}" --session w7-subscriptions run-code --filename "$script_dir/w7-playwright-capture.mjs"
"${pwcli[@]}" --session w7-subscriptions close || true

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
const expected = ['login-cold', 'home-cold', 'subscriptions-cold']
const missing = expected.filter(name => !report.scenarios?.[name])
if (missing.length) throw new Error(`missing browser smoke scenarios: ${missing.join(', ')}`)
for (const name of expected) {
  const scenario = report.scenarios[name]
  if (scenario.pageErrors?.length || scenario.consoleErrors?.length || scenario.chunk404s?.length) {
    throw new Error(`browser errors in ${name}: ${JSON.stringify({
      pageErrors: scenario.pageErrors,
      consoleErrors: scenario.consoleErrors,
      chunk404s: scenario.chunk404s
    })}`)
  }
}
const unexpectedHttpErrors = (report.raw?.httpErrorRequests || [])
    .filter(request => request.status !== 401)
if (unexpectedHttpErrors.length) {
  throw new Error(`unexpected browser fixture HTTP errors: ${JSON.stringify(unexpectedHttpErrors)}`)
}
console.log(`W7 browser smoke passed: ${expected.join(', ')}`)
NODE
