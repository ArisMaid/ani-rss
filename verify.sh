#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ui_root="$repo_root/ani-rss-ui"
pnpm_command="${ANI_RSS_PNPM:-pnpm}"
maven_command="${ANI_RSS_MVN:-mvn}"

command -v "$pnpm_command" >/dev/null 2>&1 || {
  echo 'pnpm is required; set ANI_RSS_PNPM when it is not on PATH' >&2
  exit 1
}
command -v "$maven_command" >/dev/null 2>&1 || {
  echo 'Maven is required; set ANI_RSS_MVN when it is not on PATH' >&2
  exit 1
}

echo '== Frontend locked install and tests =='
(
  cd "$ui_root"
  "$pnpm_command" install --frozen-lockfile
  "$pnpm_command" test
  "$pnpm_command" build:verify
  "$pnpm_command" check:bundle
)

echo '== Java Maven verify (no clean step) =='
(
  cd "$repo_root"
  "$maven_command" -B -Dskip.frontend=true verify --file pom.xml
)

if [[ "${ANI_RSS_SKIP_AUDIT:-0}" != '1' ]]; then
  echo '== Production dependency audit =='
  (
    cd "$ui_root"
    "$pnpm_command" audit --prod --audit-level high
  )
fi

echo 'All local verification gates passed.'
