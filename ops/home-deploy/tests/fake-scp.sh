#!/usr/bin/env bash
# fake-scp.sh — hermetic fake scp for R2B real-branch反证.
# Records argv, and copies the local tar to the FAKE_SCP_ROOT at the path encoded in the
# remote target (user@host:~/hide-nest/deploy/tmp/incoming/<dir>/<tar>). NEVER touches the
# real home host. Reads FAKE_SCP_FAIL to simulate a non-zero scp exit.
set -uo pipefail

LOG="${FAKE_SCP_LOG:-/dev/null}"
FAKE_ROOT="${FAKE_SCP_ROOT:?FAKE_SCP_ROOT required}"

printf 'scp %s\n' "$*" >> "$LOG"
# argv: -o BatchMode=yes <localTar> <user@host:~/hide-nest/deploy/tmp/incoming/<dir>/<tar>>
LOCAL="${3:-}"
DEST="${4:-}"
REMOTE="${DEST#*:}"                     # ~/hide-nest/deploy/tmp/incoming/<dir>/<tar>
REMOTE="${REMOTE#\~/hide-nest/}"        # deploy/tmp/incoming/<dir>/<tar>

if [[ -n "${FAKE_SCP_FAIL:-}" ]]; then
  exit "$FAKE_SCP_FAIL"
fi

mkdir -p "$FAKE_ROOT/$(dirname "$REMOTE")"
cp -- "$LOCAL" "$FAKE_ROOT/$REMOTE"
exit 0
