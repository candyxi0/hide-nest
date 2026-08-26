#!/usr/bin/env bash
#
# fake-remote.sh — hermetic fake HOME HOST for counter-proof tests.
#
# Creates a temporary fake ~/hide-nest layout and runs remote-deploy.sh against
# it. It NEVER connects to the real home host and NEVER performs real writes.
# The caller (Test-DeployHome.ps1) is responsible for seeding artifacts/state
# into the fake root and for precise cleanup afterwards.
#
# Usage:
#   fake-remote.sh <fake-root> [remote-deploy.sh args...]
#
# The fake root is passed through as --root so remote-deploy.sh operates on it.
set -euo pipefail

ROOT="$1"
shift

# --- create minimal fake home layout (mirrors the real host) ---
mkdir -p "$ROOT/bin" "$ROOT/run" "$ROOT/app" \
         "$ROOT/console/dist" "$ROOT/codex-adapter/dist" \
         "$ROOT/data" "$ROOT/logs" "$ROOT/deploy" "$ROOT/staging" \
         "$ROOT/runtimes"

# fake start.sh: service_start must be a safe no-op in tests
cat > "$ROOT/bin/start.sh" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$ROOT/bin/start.sh"

# skip real network smoke inside hermetic tests
export HOME_DEPLOY_SKIP_SMOKE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ENGINE="$(cd "$SCRIPT_DIR/.." && pwd -P)/remote-deploy.sh"

# exec through the SAME bash that launched us (never a WSL/system stub)
exec "$BASH" "$ENGINE" --root "$ROOT" "$@"
