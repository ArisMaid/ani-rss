#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ui_root="$repo_root/ani-rss-ui"
maven_image='maven:3.9.12-eclipse-temurin-17'
osv_image='ghcr.io/google/osv-scanner:v2.4.0'
app_image='ani-rss:verify-local'
smoke_container="ani-rss-smoke-$$"
pnpm_command="${ANI_RSS_PNPM:-pnpm}"

command -v "$pnpm_command" >/dev/null 2>&1 || {
  echo 'pnpm is required; set ANI_RSS_PNPM when it is not on PATH' >&2
  exit 1
}
command -v docker >/dev/null 2>&1 || {
  echo 'Docker is required for the Java 17 gate, OSV scan, and smoke test' >&2
  exit 1
}

cleanup_smoke() {
  if docker ps -a --filter "name=^/${smoke_container}$" --format '{{.Names}}' | grep -qx "$smoke_container"; then
    docker rm -f "$smoke_container" >/dev/null
  fi
}
trap cleanup_smoke EXIT

echo '== Frontend locked install =='
(
  cd "$ui_root"
  "$pnpm_command" install --frozen-lockfile
  "$pnpm_command" lint
  "$pnpm_command" typecheck
  "$pnpm_command" test
  "$pnpm_command" build
)

echo '== Java 17 Maven verify (tests, JaCoCo, SpotBugs) =='
docker run --rm \
  -v "$repo_root:/workspace" \
  -v "${HOME}/.m2:/root/.m2" \
  -w /workspace \
  "$maven_image" \
  mvn -B -Dskip.frontend=true verify

echo '== Production dependency audits =='
(
  cd "$ui_root"
  "$pnpm_command" audit --prod --audit-level low
)
if [[ "${ANI_RSS_SKIP_OSV:-0}" != '1' ]]; then
  docker run --rm \
    -v "$repo_root:/src:ro" \
    -w /src \
    "$osv_image" \
    scan source -L /src/ani-rss-application/target/bom.json
fi

if [[ "${ANI_RSS_SKIP_DOCKER_SMOKE:-0}" != '1' ]]; then
  echo '== Docker smoke test =='
  docker build --file "$repo_root/docker/Dockerfile" --tag "$app_image" "$repo_root"
  cleanup_smoke
  docker run -d --name "$smoke_container" \
    --tmpfs /config:rw,noexec,nosuid,size=64m \
    -p 127.0.0.1::7789 \
    "$app_image" >/dev/null

  binding="$(docker port "$smoke_container" 7789/tcp | head -n 1)"
  port="${binding##*:}"
  if [[ ! "$port" =~ ^[0-9]+$ ]]; then
    echo "Unable to determine smoke-test port from: $binding" >&2
    exit 1
  fi
  uri="http://127.0.0.1:${port}/"
  ready=0
  for _ in $(seq 1 45); do
    if body="$(curl --fail --silent --show-error --max-time 2 "$uri" 2>/dev/null)" &&
       grep -q '<div id="app"' <<<"$body"; then
      ready=1
      break
    fi
    sleep 2
  done
  if [[ "$ready" -ne 1 ]]; then
    docker logs "$smoke_container" --tail 200
    echo 'ANI-RSS did not become ready within 90 seconds' >&2
    exit 1
  fi
  cleanup_smoke
fi

echo 'All local verification gates passed.'
