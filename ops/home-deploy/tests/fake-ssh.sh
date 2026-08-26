#!/usr/bin/env bash
# fake-ssh.sh — hermetic fake ssh for R2B real-branch反证.
# Records argv and a SHA-256 of the stdin script, then returns 0 (or FAKE_SSH_FAIL).
# It does NOT run remote-deploy.sh (deploy mechanics are proven by the FakeHomeRoot e2e);
# this proves the real branch's SSH orchestration order/argv/stdin. NEVER touches the real host.
set -uo pipefail

LOG="${FAKE_SSH_LOG:-/dev/null}"
printf 'ssh %s\n' "$*" >> "$LOG"
# hash stdin (the remote-deploy.sh script body) to prove raw transmission
SCRIPT_HASH="$(sha256sum | cut -d' ' -f1)"
printf 'stdin-sha256 %s\n' "$SCRIPT_HASH" >> "$LOG"
# record the operation kind if present
case "$*" in
  *prepare-upload*)   printf 'op prepare-upload\n' >> "$LOG" ;;
  *execute-bundle*)   printf 'op execute-bundle\n' >> "$LOG" ;;
  *cleanup-upload*)   printf 'op cleanup-upload\n' >> "$LOG" ;;
  *initialize*)       printf 'op initialize\n' >> "$LOG" ;;
  *plan*)             printf 'op plan\n' >> "$LOG" ;;
esac
if [[ -n "${FAKE_SSH_FAIL:-}" ]]; then
  printf 'fail %s\n' "$FAKE_SSH_FAIL" >> "$LOG"
  exit "$FAKE_SSH_FAIL"
fi
exit 0
