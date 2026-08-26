#!/usr/bin/env bash
#
# remote-deploy.sh — hide-nest HOME HOST deploy engine (runs on the Tailscale home host).
#
# Responsibilities (fail-closed):
#   * precise path validation (staging/official targets on the same filesystem)
#   * deployment lock before ANY remote write (flock, else portable mkdir lock)
#   * local<->remote per-file hash bidirectional match before switch
#   * pre-deploy backup of every affected component + manifest
#   * atomic switch (API jar: temp+rename; console/adapter dist: directory rename)
#   * stop/start ONLY affected services; postgres/embedding left untouched (PID/ID must MATCH)
#   * automatic restore of old artifacts + original service state on failure
#   * ROLLBACK_FAILED hard stop (never continue past a failed rollback)
#   * NO_OP/EXACT when re-running the same commit+state (no duplicate restart/copy)
#   * state.json (0600) is authoritative; updated ONLY after all components succeed
#   * InitializeState only when no state + bootstrap matches + lock acquired
#   * delete/temp cleanup restricted to a REGISTERED allow-set (no $BASE/*)
#   * reject path escapes and destructive targets (HOME, ~/hide-nest root, /)
#
# V1 does NOT auto-rollback the database. New migrations return
# HOME_DEPLOY_HIGH_RISK_CONFIRMATION_REQUIRED and never reach this engine for execution.
#
# CR/LF must be 0 and `bash -n` must pass (enforced by .gitattributes + verification).
set -euo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
BASE="$HOME/hide-nest"
OPERATION="plan"
REQUEST=""
STATE=""
LOCK=""
GUARD_PATH=""
INIT_BOOTSTRAP=""
BUNDLE_TAR=""
EXPECTED_BUNDLE_SHA=""
BUNDLE_ROOT=""
INCOMING_PATH=""

usage() {
  echo "usage: $0 --root <BASE> --operation <plan|verify|execute|initialize|guardcheck> [--request <json>] [--state <json>] [--lock <lockfile>] [--guard <path>] [--bootstrap <json>]" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) BASE="$2"; shift 2;;
    --operation) OPERATION="$2"; shift 2;;
    --request) REQUEST="$2"; shift 2;;
    --state) STATE="$2"; shift 2;;
    --lock) LOCK="$2"; shift 2;;
    --guard) GUARD_PATH="$2"; shift 2;;
    --bootstrap) INIT_BOOTSTRAP="$2"; shift 2;;
    --bundle) BUNDLE_TAR="$2"; shift 2;;
    --expected-sha) EXPECTED_BUNDLE_SHA="$2"; shift 2;;
    --incoming) INCOMING_PATH="$2"; shift 2;;
    *) usage;;
  esac
done

if [[ -z "$BASE" ]]; then usage; fi
# Canonicalize BASE once so every derived path uses the same normalized form.
BASE="$(cd "$BASE" 2>/dev/null && pwd -P 2>/dev/null || printf '%s' "$BASE")"
if [[ -z "$STATE" ]]; then STATE="$BASE/deploy/state.json"; fi
if [[ -z "$LOCK" ]]; then LOCK="$BASE/deploy/deploy.lock"; fi

DEPLOY_DIR="$BASE/deploy"
STAGING_ROOT="$BASE/staging"
TMP_ROOT="$DEPLOY_DIR/tmp"
INCOMING_ROOT="$DEPLOY_DIR/tmp/incoming"
LOCKDIR=""
# Registered temp paths created this run; safe_rm_tree may ONLY remove these,
# paths under STAGING_ROOT, or paths under TMP_ROOT. NEVER $BASE/* broadly.
declare -a REGISTERED_TMP=()

# ---------------------------------------------------------------------------
# Fail-closed helpers
# ---------------------------------------------------------------------------
log() { printf '[remote-deploy] %s\n' "$*" >&2; }
die() { local code="$1"; shift; log "FATAL[$code]: $*"; exit 1; }

resolve_under() {
  local base="$1" rel="$2" resolved
  case "$rel" in
    /*) resolved="$rel" ;;
    *) resolved="$base/$rel" ;;
  esac
  local canon
  canon="$(cd "$(dirname "$resolved")" 2>/dev/null && pwd -P 2>/dev/null)/$(basename "$resolved")" || canon="$resolved"
  local basecanon
  basecanon="$(cd "$base" 2>/dev/null && pwd -P 2>/dev/null)" || basecanon="$base"
  case "$canon" in
    "$basecanon"/*) echo "$canon";;
    *) die "PATH_ESCAPE" "path escaped deploy root: $rel";;
  esac
}

# Reject destructive targets: / , HOME, and the deploy root itself.
assert_safe_destroy() {
  local path
  path="$(cd "$1" 2>/dev/null && pwd -P 2>/dev/null || printf '%s' "$1")"
  local home
  home="$(cd "$HOME" 2>/dev/null && pwd -P 2>/dev/null || echo "$HOME")"
  local rootcanon
  rootcanon="$(cd "$BASE" 2>/dev/null && pwd -P 2>/dev/null)" || rootcanon="$BASE"
  if [[ "$path" == "/" || "$path" == "$home" || "$path" == "$rootcanon" ]]; then
    die "DESTRUCTIVE_TARGET" "refusing destructive operation on $1"
  fi
}

# Formal production directories under BASE that safe_rm_tree must NEVER touch.
formal_base_dir() {
  local p="$1"
  local rootcanon
  rootcanon="$(cd "$BASE" 2>/dev/null && pwd -P 2>/dev/null)" || rootcanon="$BASE"
  case "$p" in
    "$rootcanon"/app|"$rootcanon"/app/*|"$rootcanon"/console|"$rootcanon"/console/*| \
    "$rootcanon"/codex-adapter|"$rootcanon"/codex-adapter/*|"$rootcanon"/data|"$rootcanon"/data/*| \
    "$rootcanon"/logs|"$rootcanon"/logs/*|"$rootcanon"/bin|"$rootcanon"/bin/*| \
    "$rootcanon"/runtimes|"$rootcanon"/runtimes/*|"$rootcanon"/deploy|"$rootcanon") return 0;;
  esac
  return 1
}

# safe_rm_tree: ONLY registered staging/tmp/aside paths are removable. No $BASE/*.
safe_rm_tree() {
  local target="$1"
  if [[ "$target" != /* ]]; then
    die "PATH_ESCAPE" "refusing to remove non-absolute path: $target"
  fi
  if formal_base_dir "$target"; then
    die "DESTRUCTIVE_TARGET" "refusing to remove formal production path: $target"
  fi
  local allowed=0
  case "$target" in
    "$STAGING_ROOT"/*|"$TMP_ROOT"/*) allowed=1;;
  esac
  if [[ "$allowed" -eq 0 ]]; then
    local t
    for t in "${REGISTERED_TMP[@]+"${REGISTERED_TMP[@]}"}"; do
      if [[ "$target" == "$t" ]]; then allowed=1; break; fi
    done
  fi
  if [[ "$allowed" -eq 0 ]]; then
    die "PATH_ESCAPE" "refusing to remove path outside registered temp set: $target"
  fi
  assert_safe_destroy "$target"
  rm -rf -- "$target"
}

register_tmp() { REGISTERED_TMP+=("$1"); }

sha256_file() { sha256sum "$1" | cut -d' ' -f1; }

# R2-12: path-bound collection hash. Each file contributes "relativePath:SHA256"; the
# sorted lines are hashed. Renaming / missing / extra / same-content-diff-path all change
# the hash (content-only sorting would hide renames).
dir_collection_hash() {
  local dir="$1"
  if [[ ! -d "$dir" ]]; then die "ARTIFACT_MISSING" "directory missing: $dir"; fi
  find "$dir" -type f -print0 | sort -z | while IFS= read -r -d '' f; do
    local rel="${f#"$dir"/}"
    printf '%s:%s\n' "$rel" "$(sha256sum "$f" | cut -d' ' -f1)"
  done | sha256sum | cut -d' ' -f1
}

jq_ok() { command -v jq >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------
load_state() {
  if [[ -f "$STATE" ]]; then
    local c; c="$(cat "$STATE")"
    c="${c#$'\xef\xbb\xbf'}"   # strip a leading UTF-8 BOM if present
    printf '%s' "$c"
  else
    echo ""
  fi
}

state_commit_for() {
  local comp="$1" json="$2"
  if [[ -z "$json" ]]; then echo ""; return; fi
  if jq_ok; then
    jq -r ".${comp}Commit // \"\"" <<<"$json" 2>/dev/null || echo ""
  else
    echo ""
  fi
}

# ---------------------------------------------------------------------------
# Lock
# ---------------------------------------------------------------------------
acquire_lock() {
  mkdir -p "$DEPLOY_DIR"
  if command -v flock >/dev/null 2>&1; then
    exec 9>"$LOCK"
    if ! flock -n 9; then die "LOCK_CONFLICT" "deployment lock held by another process: $LOCK"; fi
  else
    LOCKDIR="${LOCK}.d"
    local i
    for i in $(seq 1 50); do
      if mkdir "$LOCKDIR" 2>/dev/null; then
        printf '%s\n' "$$" > "$LOCKDIR/pid"
        trap '[ -n "${LOCKDIR:-}" ] && rm -rf -- "$LOCKDIR"' EXIT
        break
      fi
      sleep 0.1
      if [ "$i" -eq 50 ]; then die "LOCK_CONFLICT" "deployment lock held by another process: $LOCK"; fi
    done
  fi
  log "lock acquired: $LOCK"
}

# ---------------------------------------------------------------------------
# Hash verification
# ---------------------------------------------------------------------------
artifact_hash_of() {
  # $1 = type (jar|dir) ; $2 = abs path
  local type="$1" p="$2"
  if [[ "$type" == "jar" ]]; then
    [[ -f "$p" ]] || die "ARTIFACT_MISSING" "jar missing: $p"
    sha256_file "$p"
  elif [[ "$type" == "dir" ]]; then
    [[ -d "$p" ]] || die "ARTIFACT_MISSING" "dir missing: $p"
    dir_collection_hash "$p"
  else
    die "BAD_REQUEST" "unknown artifact type: $type"
  fi
}

# R2-06: a request staging path resolves under the unpacked bundle root when executing a
# bundle; otherwise under BASE. Windows/MSYS paths are always rejected.
resolve_staging() {
  if [[ "$1" == [A-Za-z]:* || "$1" == /[A-Za-z]/* ]]; then
    die "PATH_ESCAPE" "Windows/MSYS path rejected in request: $1"
  fi
  local base="$BASE"
  if [[ -n "$BUNDLE_ROOT" ]]; then base="$BUNDLE_ROOT"; fi
  resolve_under "$base" "$1"
}

verify_artifact_hash() {
  local comp="$1"
  local type target staging expect
  type="$(jq_ok && jq -r --arg c "$comp" '.components[$c].type' <<<"$JSON_ROOT" 2>/dev/null || echo "")"
  target="$(jq_ok && jq -r --arg c "$comp" '.components[$c].target' <<<"$JSON_ROOT" 2>/dev/null || echo "")"
  staging="$(jq_ok && jq -r --arg c "$comp" '.components[$c].staging' <<<"$JSON_ROOT" 2>/dev/null || echo "")"
  expect="$(jq_ok && jq -r --arg c "$comp" '.components[$c].artifactHash' <<<"$JSON_ROOT" 2>/dev/null || echo "")"
  if [[ -z "$type" || -z "$target" || -z "$staging" || -z "$expect" ]]; then
    die "BAD_REQUEST" "component $comp missing switch metadata"
  fi
  local staging_abs
  staging_abs="$(resolve_staging "$staging")"
  local got
  got="$(artifact_hash_of "$type" "$staging_abs")"
  if [[ "$got" != "$expect" ]]; then
    die "HASH_MISMATCH" "component $comp remote staged hash $got != expected $expect"
  fi
  log "hash verified: $comp ($got)"
}

# ---------------------------------------------------------------------------
# Backup
# ---------------------------------------------------------------------------
backup_component() {
  local comp="$1" target="$2" type="$3" rb="$4"
  local target_abs rb_abs
  target_abs="$(resolve_under "$BASE" "$target")"
  rb_abs="$(resolve_under "$BASE" "$rb")"
  mkdir -p "$rb_abs"
  if [[ "$type" == "jar" ]]; then
    if [[ -f "$target_abs" ]]; then
      cp -p "$target_abs" "$rb_abs/$(basename "$target_abs")"
    fi
    echo "$(sha256_file "$rb_abs/$(basename "$target_abs")" 2>/dev/null || echo missing)" > "$rb_abs/manifest.txt"
  else
    local name; name="$(basename "$target_abs")"
    if [[ -d "$target_abs" ]]; then
      cp -a "$target_abs" "$rb_abs/$name"
    fi
    find "$rb_abs" -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null > "$rb_abs/manifest.txt" || true
  fi
  log "backup complete: $comp -> $rb"
}

# ---------------------------------------------------------------------------
# Service control (only affected services; never fake success)
# ---------------------------------------------------------------------------
BIN="$BASE/bin"

record_service_state() {
  # R2-13: fake services must be observable (running/stopped/pid) for rollback反证.
  local dir="$BASE/run"
  mkdir -p "$dir"
  printf '%s %s %s\n' "$1" "$2" "$3" >> "$dir/services.log"
}

service_stop() {
  local name="$1"
  if [[ "${HOME_DEPLOY_FAKE_SERVICES:-0}" == "1" ]]; then
    # fake (hermetic test) service control: no real process, no aliveness check
    record_service_state stop "$name" "fake"
    rm -f -- "$BASE/run/$name.pid"
    log "stopped service (fake): $name"
    return 0
  fi
  local pidfile="$BASE/run/$name.pid" pid i
  if [[ -f "$pidfile" ]]; then
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      # wait for process to actually exit (timeout)
      for i in $(seq 1 50); do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 0.1
      done
      if kill -0 "$pid" 2>/dev/null; then
        log "stop failed: $name (pid $pid did not exit in time)"
        return 1
      fi
    fi
    rm -f -- "$pidfile"
  fi
  log "stopped service: $name"
  return 0
}

service_start() {
  local name="$1"
  local pidfile="$BASE/run/$name.pid"
  if [[ "${HOME_DEPLOY_FAKE_SERVICES:-0}" == "1" ]]; then
    "$BIN/start.sh" >/dev/null 2>&1 || { log "start failed: $name"; return 1; }
    mkdir -p "$(dirname "$pidfile")"
    printf 'fake\n' > "$pidfile"
    record_service_state start "$name" "fake"
    log "started service (fake): $name"
    return 0
  fi
  if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    log "service already running: $name"
    return 0
  fi
  "$BIN/start.sh" >/dev/null 2>&1 || { log "start failed: $name"; return 1; }
  # wait for a live pid file
  local i
  for i in $(seq 1 50); do
    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      log "started service: $name (pid $(cat "$pidfile"))"
      return 0
    fi
    sleep 0.1
  done
  log "start not confirmed: $name (no live pid)"
  return 1
}

# ---------------------------------------------------------------------------
# Atomic switch
# ---------------------------------------------------------------------------
atomic_switch_jar() {
  local comp="$1" target="$2" staging="$3" rb="$4"
  if [[ "${HOME_DEPLOY_TEST_FAIL_COMPONENT:-}" == "$comp" ]]; then
    log "TEST fault injected: switch fail for $comp"; return 1
  fi
  local target_abs staging_abs
  target_abs="$(resolve_under "$BASE" "$target")"
  staging_abs="$(resolve_staging "$staging")"
  local tmp="$TMP_ROOT/$comp.jar.$$"
  mkdir -p "$TMP_ROOT"
  register_tmp "$tmp"
  if ! cp "$staging_abs" "$tmp"; then log "jar copy failed for $comp"; return 1; fi
  if ! mv -f "$tmp" "$target_abs"; then
    safe_rm_tree "$tmp" 2>/dev/null || rm -f -- "$tmp"
    log "jar rename failed for $comp"; return 1
  fi
  log "atomic jar switch OK: $comp"
}

atomic_switch_dir() {
  local comp="$1" target="$2" staging="$3" rb="$4"
  if [[ "${HOME_DEPLOY_TEST_FAIL_COMPONENT:-}" == "$comp" ]]; then
    log "TEST fault injected: switch fail for $comp"; return 1
  fi
  local target_abs staging_abs aside
  target_abs="$(resolve_under "$BASE" "$target")"
  staging_abs="$(resolve_staging "$staging")"
  aside="${TMP_ROOT}/$comp-dist-aside.$$"
  mkdir -p "$TMP_ROOT"
  register_tmp "$aside"
  if [[ -d "$target_abs" ]]; then
    if ! mv "$target_abs" "$aside"; then log "dir switch failed for $comp (target move)"; return 1; fi
  else
    aside=""
  fi
  if ! mv "$staging_abs" "$target_abs"; then
    if [[ -n "$aside" && -d "$aside" ]]; then mv "$aside" "$target_abs" 2>/dev/null || true; fi
    log "dir switch failed for $comp"; return 1
  fi
  if [[ -n "$aside" && -d "$aside" ]]; then
    safe_rm_tree "$aside"
  fi
  log "atomic dir switch OK: $comp"
}

# ---------------------------------------------------------------------------
# Rollback (restore old artifact + verify; restore service state)
# ---------------------------------------------------------------------------
rollback_component() {
  local comp="$1" target="$2" type="$3" rb="$4"
  if [[ "${HOME_DEPLOY_TEST_FAIL_ROLLBACK:-0}" == "1" ]]; then
    die "ROLLBACK_FAILED" "test fault: rollback failed for $comp"
  fi
  local target_abs rb_abs
  target_abs="$(resolve_under "$BASE" "$target")"
  rb_abs="$(resolve_under "$BASE" "$rb")"
  if [[ "$type" == "jar" ]]; then
    local backup_jar="$rb_abs/$(basename "$target_abs")"
    [[ -f "$backup_jar" ]] || die "ROLLBACK_FAILED" "no backup jar for $comp"
    local expect
    expect="$(head -n1 "$rb_abs/manifest.txt" 2>/dev/null || echo missing)"
    local tmp="$TMP_ROOT/$comp.jar.rollback.$$"
    mkdir -p "$TMP_ROOT"; register_tmp "$tmp"
    cp "$backup_jar" "$tmp" && mv -f "$tmp" "$target_abs" || { die "ROLLBACK_FAILED" "jar rollback failed for $comp"; }
    local got; got="$(sha256_file "$target_abs")"
    if [[ -n "$expect" && "$expect" != "missing" && "$got" != "$expect" ]]; then
      die "ROLLBACK_FAILED" "jar rollback hash verify failed for $comp"
    fi
  else
    local name; name="$(basename "$target_abs")"
    local backup_tree="$rb_abs/$name"
    [[ -d "$backup_tree" ]] || die "ROLLBACK_FAILED" "no backup tree for $comp"
    local aside="$TMP_ROOT/$comp-dist-rollback-aside.$$"
    mkdir -p "$TMP_ROOT"; register_tmp "$aside"
    mv "$target_abs" "$aside" 2>/dev/null || true
    mv "$backup_tree" "$target_abs" || { die "ROLLBACK_FAILED" "dir rollback failed for $comp"; }
    safe_rm_tree "$aside" 2>/dev/null || rm -rf -- "$aside"
  fi
  log "rolled back: $comp"
}

# ---------------------------------------------------------------------------
# Smoke (V1: generic loopback + HTTP health only — scoped claims)
# ---------------------------------------------------------------------------
smoke_basic() {
  if [[ "${HOME_DEPLOY_SKIP_SMOKE:-0}" == "1" ]]; then
    log "smoke basic: SKIPPED (test)"; return 0
  fi
  # 1) four loopback-only listeners
  if command -v ss >/dev/null 2>&1; then
    local port
    for port in 4173 8080 5432 8090; do
      local line
      line="$(ss -H -ltn 2>/dev/null | grep ":$port " || true)"
      if [[ -z "$line" ]]; then log "smoke FAIL: port $port not listening"; return 1; fi
      case "$line" in
        127.0.0.1:*|"[::ffff:127.0.0.1]"*) ;;
        *) log "smoke FAIL: port $port not loopback-only"; return 1;;
      esac
    done
  fi
  # 2) HTTP health probes when curl is available
  if command -v curl >/dev/null 2>&1; then
    local console_code
    console_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:4173/ 2>/dev/null || echo 000)"
    if [[ "$console_code" != "200" ]]; then log "smoke FAIL: console http $console_code"; return 1; fi
  fi
  log "smoke basic: loopback-only + http health OK (V1 generic scope)"
  return 0
}

# ---------------------------------------------------------------------------
# State update
# ---------------------------------------------------------------------------
write_state() {
  mkdir -p "$DEPLOY_DIR"
  local tmp="$TMP_ROOT/state.json.tmp.$$"
  mkdir -p "$TMP_ROOT"; register_tmp "$tmp"
  printf '%s\n' "$1" > "$tmp"
  mv -f "$tmp" "$STATE"
  chmod 600 "$STATE" 2>/dev/null || true
  log "state updated"
}

# ---------------------------------------------------------------------------
# Operations
# ---------------------------------------------------------------------------
op_plan() {
  # Read-only: emit host state facts (no writes at all).
  local json; json="$(load_state)"
  echo "{"
  echo "  \"operation\": \"plan\","
  echo "  \"base\": \"$BASE\","
  echo "  \"hasState\": $([[ -n "$json" ]] && echo true || echo false),"
  echo "  \"state\": ${json:-null},"
  echo "  \"apiPid\": \"$(cat "$BASE/run/api.pid" 2>/dev/null || echo absent)\","
  echo "  \"consolePid\": \"$(cat "$BASE/run/console.pid" 2>/dev/null || echo absent)\","
  echo "  \"apiJarHash\": \"$(sha256_file "$BASE/app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar" 2>/dev/null || echo missing)\","
  echo "  \"consoleDistHash\": \"$(dir_collection_hash "$BASE/console/dist" 2>/dev/null || echo missing)\","
  echo "  \"adapterDistHash\": \"$(dir_collection_hash "$BASE/codex-adapter/dist" 2>/dev/null || echo missing)\","
  echo "  \"writes\": 0"
  echo "}"
}

op_initialize() {
  # Initialize state from bootstrap ONLY when: no existing state, bootstrap present,
  # bootstrap matches real artifact/migration, and lock is acquired.
  [[ -f "$INIT_BOOTSTRAP" ]] || die "BAD_REQUEST" "initialize requires --bootstrap"
  jq_ok || die "MISSING_JQ" "jq required for initialize"
  acquire_lock
  local existing; existing="$(load_state)"
  if [[ -n "$existing" ]]; then
    # existing state: same-value replay is EXACT/NO_OP; differing value is rejected
    local bapi bcon bada bpipe
    bapi="$(jq -r '.apiCommit // ""' "$INIT_BOOTSTRAP")"
    bcon="$(jq -r '.consoleCommit // ""' "$INIT_BOOTSTRAP")"
    bada="$(jq -r '.adapterCommit // ""' "$INIT_BOOTSTRAP")"
    bpipe="$(jq -r '.pipelineCommit // ""' "$INIT_BOOTSTRAP")"
    local eapi econ eada epipe
    eapi="$(jq -r '.apiCommit // ""' <<<"$existing")"
    econ="$(jq -r '.consoleCommit // ""' <<<"$existing")"
    eada="$(jq -r '.adapterCommit // ""' <<<"$existing")"
    epipe="$(jq -r '.pipelineCommit // ""' <<<"$existing")"
    if [[ "$bapi" == "$eapi" && "$bcon" == "$econ" && "$bada" == "$eada" && "$bpipe" == "$epipe" ]]; then
      echo '{"operation":"initialize","result":"EXACT_NO_OP","writes":0}'
      return 0
    fi
    die "STATE_CONFLICT" "existing state differs from bootstrap; refusing to overwrite"
  fi
  # no existing state: validate bootstrap against real artifact hashes (read-only)
  local real_api real_con real_ada
  real_api="$(sha256_file "$BASE/app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar" 2>/dev/null || echo missing)"
  real_con="$(dir_collection_hash "$BASE/console/dist" 2>/dev/null || echo missing)"
  real_ada="$(dir_collection_hash "$BASE/codex-adapter/dist" 2>/dev/null || echo missing)"
  local want_api want_con want_ada
  want_api="$(jq -r '.artifactHashes.api // ""' "$INIT_BOOTSTRAP")"
  want_con="$(jq -r '.artifactHashes.console // ""' "$INIT_BOOTSTRAP")"
  want_ada="$(jq -r '.artifactHashes.adapter // ""' "$INIT_BOOTSTRAP")"
  if [[ -n "$want_api" && "$want_api" != "$real_api" ]]; then die "BOOTSTRAP_MISMATCH" "api artifact hash mismatch"; fi
  if [[ -n "$want_con" && "$want_con" != "$real_con" ]]; then die "BOOTSTRAP_MISMATCH" "console artifact hash mismatch"; fi
  if [[ -n "$want_ada" && "$want_ada" != "$real_ada" ]]; then die "BOOTSTRAP_MISMATCH" "adapter artifact hash mismatch"; fi
  # write authoritative state (0600) from bootstrap
  local new_state
  new_state="$(jq -n \
    --arg api "$(jq -r '.apiCommit // ""' "$INIT_BOOTSTRAP")" \
    --arg console "$(jq -r '.consoleCommit // ""' "$INIT_BOOTSTRAP")" \
    --arg adapter "$(jq -r '.adapterCommit // ""' "$INIT_BOOTSTRAP")" \
    --arg pipeline "$(jq -r '.pipelineCommit // ""' "$INIT_BOOTSTRAP")" \
    --argjson fw "$(jq -r '.flywayMaxVersion // 0' "$INIT_BOOTSTRAP")" \
    --arg mh "$(jq -r '.migrationManifestHash // ""' "$INIT_BOOTSTRAP")" \
    --argjson mig "$(jq -c '.migrationManifest // {}' "$INIT_BOOTSTRAP")" \
    --arg apiH "$real_api" \
    --arg conH "$real_con" \
    --arg adaH "$real_ada" \
    --arg pg "$(jq -r '.hostInvariants.postgresContainerId // ""' "$INIT_BOOTSTRAP")" \
    --arg emb "$(jq -r '.hostInvariants.embeddingContainerId // ""' "$INIT_BOOTSTRAP")" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{schemaVersion:1, apiCommit:$api, consoleCommit:$console, adapterCommit:$adapter, pipelineCommit:$pipeline, flywayMaxVersion:$fw, migrationManifestHash:$mh, migrationManifest:$mig, artifactHashes:{api:$apiH, console:$conH, adapter:$adaH}, hostInvariants:{postgresContainerId:$pg, embeddingContainerId:$emb}, updatedAt:$ts}')"
  write_state "$new_state"
  echo '{"operation":"initialize","result":"HOME_DEPLOY_STATE_INITIALIZED","writes":1}'
}

# R2-09: PostgreSQL/Embedding container IDs must MATCH request and never be empty-bypassed.
# Skipped only in hermetic fake tests (no real docker).
check_host_invariants() {
  if [[ "${HOME_DEPLOY_FAKE_SERVICES:-0}" == "1" ]]; then return 0; fi
  jq_ok || return 0
  local want_pg want_emb pg emb
  want_pg="$(jq -r '.hostInvariants.postgresContainerId // ""' <<<"$json" 2>/dev/null || echo "")"
  want_emb="$(jq -r '.hostInvariants.embeddingContainerId // ""' <<<"$json" 2>/dev/null || echo "")"
  if [[ -z "$want_pg" || -z "$want_emb" ]]; then die "INVARIANT_MISSING" "host invariants required (empty bypass forbidden)"; fi
  pg="$(docker ps --filter name=hide-nest-postgres --format '{{.ID}}' 2>/dev/null || echo "")"
  emb="$(docker ps --filter name=hide-embedding-candidate --format '{{.ID}}' 2>/dev/null || echo "")"
  if [[ "$want_pg" != "$pg" ]]; then die "INVARIANT_MISMATCH" "postgres container ID drifted"; fi
  if [[ "$want_emb" != "$emb" ]]; then die "INVARIANT_MISMATCH" "embedding container ID drifted"; fi
}

op_verify() {
  [[ -f "$REQUEST" ]] || die "BAD_REQUEST" "no request file"
  local json; json="$(cat "$REQUEST")"
  export JSON_ROOT="$json"
  for comp in api console adapter; do
    local sw
    sw="$(jq_ok && jq -r --arg c "$comp" '.components[$c].switch // false' <<<"$json" 2>/dev/null || echo false)"
    if [[ "$sw" == "true" ]]; then verify_artifact_hash "$comp"; fi
  done
  check_host_invariants
  echo '{"operation":"verify","result":"VERIFY_OK","writes":0}'
}

# R2A-03: after unpack and before any switch, recompute the components/** manifest and
# compare it bidirectionally (file count, relative-path set, per-file hash) against
# manifest.tsv inside the bundle. Rejects rename/missing/extra/duplicate/tamper/same-content-diff-path.
verify_bundle_manifest() {
  [[ -n "$BUNDLE_ROOT" ]] || return 0
  local manifest_file="$BUNDLE_ROOT/manifest.tsv"
  [[ -f "$manifest_file" ]] || die "MANIFEST_MISSING" "manifest.tsv missing in bundle"
  declare -A expected=()
  local expected_count=0
  while IFS=$'\t' read -r rel sha; do
    [[ -n "$rel" ]] || continue
    case "$rel" in
      /*|../*|*\\*|[A-Za-z]:*) die "MANIFEST_TAMPER" "invalid manifest path: $rel";;
    esac
    if [[ -n "${expected[$rel]:-}" ]]; then die "MANIFEST_DUP" "duplicate manifest path: $rel"; fi
    [[ "$sha" =~ ^[0-9a-f]{64}$ ]] || die "MANIFEST_TAMPER" "bad hash for $rel"
    expected["$rel"]="$sha"
    expected_count=$((expected_count+1))
  done < "$manifest_file"

  declare -A actual=()
  local actual_count=0
  local f
  while IFS= read -r -d '' f; do
    local rel="${f#"$BUNDLE_ROOT"/}"
    case "$rel" in
      components/*) ;;
      *) continue;;
    esac
    actual["$rel"]="$(sha256_file "$f")"
    actual_count=$((actual_count+1))
  done < <(find "$BUNDLE_ROOT/components" -type f -print0 2>/dev/null || true)

  if [[ "$expected_count" != "$actual_count" ]]; then die "MANIFEST_COUNT" "manifest $expected_count != actual $actual_count"; fi
  local rel
  for rel in "${!expected[@]}"; do
    if [[ -z "${actual[$rel]:-}" ]]; then die "MANIFEST_MISSING_FILE" "bundle missing: $rel"; fi
    if [[ "${actual[$rel]}" != "${expected[$rel]}" ]]; then die "MANIFEST_HASH" "hash mismatch: $rel"; fi
  done
  for rel in "${!actual[@]}"; do
    if [[ -z "${expected[$rel]:-}" ]]; then die "MANIFEST_EXTRA" "unexpected file: $rel"; fi
  done
  log "bundle manifest verified ($expected_count files)"
}

# op_execute_locked: full execute body, ASSUMES the deploy lock is already held.
# Both op_execute (direct) and op_execute_bundle (bundle) call this after acquiring the
# lock exactly once — no double acquire/release (R2-05/06/07 #6).
op_execute_locked() {
  [[ -f "$REQUEST" ]] || die "BAD_REQUEST" "no request file"
  local json; json="$(cat "$REQUEST")"
  export JSON_ROOT="$json"
  jq_ok || die "MISSING_JQ" "jq required for execute"

  local target_commit target_short
  target_commit="$(jq -r '.targetCommit // ""' <<<"$json")"
  target_short="$(jq -r '.targetShort // ""' <<<"$json")"
  [[ -n "$target_commit" ]] || die "BAD_REQUEST" "missing targetCommit"

  mkdir -p "$STAGING_ROOT" "$TMP_ROOT"

  local state_json; state_json="$(load_state)"

  # --- NO_OP detection
  local all_noop=1
  declare -A DO_SWITCH=()
  for comp in api console adapter; do
    local sw
    sw="$(jq -r --arg c "$comp" '.components[$c].switch // false' <<<"$json")"
    DO_SWITCH[$comp]="$sw"
    if [[ "$sw" != "true" ]]; then continue; fi
    local want_hash state_hash cur_commit state_commit
    want_hash="$(jq -r --arg c "$comp" '.components[$c].artifactHash // ""' <<<"$json")"
    state_hash="$(jq -r --arg c "$comp" '.artifactHashes[$c] // ""' <<<"$state_json" 2>/dev/null || echo "")"
    cur_commit="$(jq -r --arg c "$comp" '.components[$c].commit // ""' <<<"$json")"
    state_commit="$(jq -r --arg c "$comp" ".${comp}Commit // \"\"" <<<"$state_json" 2>/dev/null || echo "")"
    if [[ -n "$state_commit" && "$cur_commit" == "$state_commit" && -n "$want_hash" && "$want_hash" == "$state_hash" ]]; then
      log "NO_OP component $comp (already at $cur_commit, artifact hash matches)"
      DO_SWITCH[$comp]="skip"
    else
      all_noop=0
    fi
  done
  if [[ "$all_noop" -eq 1 && "$(jq -r '.migration.switch // false' <<<"$json")" != "true" ]]; then
    echo '{"operation":"execute","result":"NO_OP","writes":0}'
    return 0
  fi

  # --- verify staged hashes before any write
  for comp in api console adapter; do
    if [[ "${DO_SWITCH[$comp]}" == "true" ]]; then verify_artifact_hash "$comp"; fi
  done
  # R2-09: container IDs must MATCH before any switch
  check_host_invariants
  # R2A-03: bundle manifest must verify before any switch
  verify_bundle_manifest

  local ts rb
  ts="$(date +%Y%m%d-%H%M%S)"
  rb="rollback-before-${target_short}-${ts}"

  # --- backups
  for comp in api console adapter; do
    if [[ "${DO_SWITCH[$comp]}" != "true" ]]; then continue; fi
    local t type
    t="$(jq -r --arg c "$comp" '.components[$c].target' <<<"$json")"
    type="$(jq -r --arg c "$comp" '.components[$c].type' <<<"$json")"
    backup_component "$comp" "$t" "$type" "$rb"
  done

  # --- stop API if it will be switched (record prior running state for restore)
  local api_was_running=0 api_switched=0
  if [[ "${DO_SWITCH[api]}" == "true" ]]; then
    if [[ -f "$BASE/run/api.pid" ]] && kill -0 "$(cat "$BASE/run/api.pid")" 2>/dev/null; then api_was_running=1; fi
    service_stop api || die "STOP_FAILED" "failed to stop api before switch"
    api_switched=1
  fi

  # --- atomic switches with rollback on failure (restore artifacts + service state)
  local switched=()
  rollback_all() {
    # roll back this component + all previously switched; then restore service state
    for prev in "${switched[@]+"${switched[@]}"}"; do
      local pt ptype
      pt="$(jq -r --arg c "$prev" '.components[$c].target' <<<"$json")"
      ptype="$(jq -r --arg c "$prev" '.components[$c].type' <<<"$json")"
      rollback_component "$prev" "$pt" "$ptype" "$rb" || die "ROLLBACK_FAILED" "rollback failed for $prev"
    done
    if [[ "$api_switched" -eq 1 && "$api_was_running" -eq 1 ]]; then
      service_start api || die "ROLLBACK_FAILED" "failed to restore api service after rollback"
    fi
  }

  for comp in api console adapter; do
    if [[ "${DO_SWITCH[$comp]}" != "true" ]]; then continue; fi
    local t type staging
    t="$(jq -r --arg c "$comp" '.components[$c].target' <<<"$json")"
    type="$(jq -r --arg c "$comp" '.components[$c].type' <<<"$json")"
    staging="$(jq -r --arg c "$comp" '.components[$c].staging' <<<"$json")"
    if [[ "$type" == "jar" ]]; then
      if ! atomic_switch_jar "$comp" "$t" "$staging" "$rb"; then
        log "switch failed for $comp; rolling back all"
        rollback_component "$comp" "$t" "$type" "$rb" || die "ROLLBACK_FAILED" "rollback failed for $comp"
        rollback_all
        die "DEPLOY_FAILED_ROLLED_BACK" "component $comp failed and was rolled back"
      fi
    else
      if ! atomic_switch_dir "$comp" "$t" "$staging" "$rb"; then
        log "switch failed for $comp; rolling back all"
        rollback_component "$comp" "$t" "$type" "$rb" || die "ROLLBACK_FAILED" "rollback failed for $comp"
        rollback_all
        die "DEPLOY_FAILED_ROLLED_BACK" "component $comp failed and was rolled back"
      fi
    fi
    switched+=("$comp")
  done

  # --- start API if it was switched
  if [[ "$api_switched" -eq 1 ]]; then
    service_start api || {
      log "api start failed after switch; rolling back all"
      rollback_all
      die "START_FAILED_ROLLED_BACK" "api failed to start; rolled back"
    }
  fi

  # --- smoke; on failure stop new API (if switched), restore artifacts, restart old API
  if ! smoke_basic; then
    log "smoke failed after switch; rolling back"
    if [[ "$api_switched" -eq 1 ]]; then service_stop api || true; fi
    for prev in "${switched[@]+"${switched[@]}"}"; do
      local pt3 ptype3
      pt3="$(jq -r --arg c "$prev" '.components[$c].target' <<<"$json")"
      ptype3="$(jq -r --arg c "$prev" '.components[$c].type' <<<"$json")"
      rollback_component "$prev" "$pt3" "$ptype3" "$rb" || die "ROLLBACK_FAILED" "rollback failed for $prev"
    done
    if [[ "$api_was_running" -eq 1 ]]; then
      service_start api || die "ROLLBACK_FAILED" "failed to restart old api after smoke rollback"
    fi
    die "SMOKE_FAILED_ROLLED_BACK" "basic smoke failed; rolled back"
  fi

  # --- update state ONLY after full success.
  # For each component, keep the EXISTING state's commit/hash unless it was
  # switched this run (only the affected set is advanced).
  compv() { local c="$1" sw; sw="$(jq -r --arg c "$c" '.components[$c].switch // false' <<<"$json")"; if [[ "$sw" == "true" ]]; then jq -r --arg c "$c" '.components[$c].commit' <<<"$json"; else jq -r --arg c "$c" '.[$c + "Commit"] // ""' <<<"$state_json"; fi; }
  hashv() { local c="$1" sw; sw="$(jq -r --arg c "$c" '.components[$c].switch // false' <<<"$json")"; if [[ "$sw" == "true" ]]; then jq -r --arg c "$c" '.components[$c].artifactHash' <<<"$json"; else jq -r --arg c "$c" '.artifactHashes[$c] // ""' <<<"$state_json"; fi; }
  local api_c console_c adapter_c api_h console_h adapter_h
  api_c="$(compv api)"; console_c="$(compv console)"; adapter_c="$(compv adapter)"
  api_h="$(hashv api)"; console_h="$(hashv console)"; adapter_h="$(hashv adapter)"
  local new_state
  new_state="$(jq -n \
    --arg api "$api_c" --arg console "$console_c" --arg adapter "$adapter_c" \
    --arg pipeline "$(jq -r '.pipelineCommit // ""' <<<"$json")" \
    --argjson fw "$(jq -r '.flywayMaxVersion // 0' <<<"$json")" \
    --arg mh "$(jq -r '.migrationManifestHash // ""' <<<"$json")" \
    --argjson mig "$(jq -c '.migrationManifest // {}' <<<"$json")" \
    --arg apiH "$api_h" --arg conH "$console_h" --arg adaH "$adapter_h" \
    --arg pg "$(jq -r '.hostInvariants.postgresContainerId // ""' <<<"$json")" \
    --arg emb "$(jq -r '.hostInvariants.embeddingContainerId // ""' <<<"$json")" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{schemaVersion:1, apiCommit:$api, consoleCommit:$console, adapterCommit:$adapter, pipelineCommit:$pipeline, flywayMaxVersion:$fw, migrationManifestHash:$mh, migrationManifest:$mig, artifactHashes:{api:$apiH, console:$conH, adapter:$adaH}, hostInvariants:{postgresContainerId:$pg, embeddingContainerId:$emb}, updatedAt:$ts}')"
  write_state "$new_state"

  echo "{\"operation\":\"execute\",\"result\":\"HOME_DEPLOY_BASIC_GATES_PASS_READY_FOR_XIAOLIN_QA\",\"rollbackDir\":\"$rb\",\"writes\":1}"
}

# Direct (non-bundle) execute: acquire the lock once, then run the locked body.
op_execute() {
  acquire_lock
  op_execute_locked
}

# R2A-01: prepare-upload creates the precise incoming dir, rejecting an existing one.
op_prepare_upload() {
  [[ -n "$INCOMING_PATH" ]] || die "BAD_REQUEST" "prepare-upload requires --incoming"
  local inc; inc="$(resolve_under "$BASE" "$INCOMING_PATH")"
  case "$inc" in
    "$INCOMING_ROOT"/*) ;;
    *) die "PATH_ESCAPE" "incoming outside precise root: $INCOMING_PATH";;
  esac
  if [[ -e "$inc" ]]; then die "INCOMING_CONFLICT" "incoming dir already exists: $INCOMING_PATH"; fi
  mkdir -p "$inc"
  echo "{\"operation\":\"prepare-upload\",\"result\":\"PREPARED\",\"incoming\":\"$INCOMING_PATH\"}"
}

# R2A-01: cleanup-upload removes ONLY this run's precise incoming dir.
op_cleanup_upload() {
  [[ -n "$INCOMING_PATH" ]] || die "BAD_REQUEST" "cleanup-upload requires --incoming"
  local inc; inc="$(resolve_under "$BASE" "$INCOMING_PATH")"
  case "$inc" in
    "$INCOMING_ROOT"/*) ;;
    *) die "PATH_ESCAPE" "incoming outside precise root: $INCOMING_PATH";;
  esac
  safe_rm_tree "$inc"
  echo '{"operation":"cleanup-upload","result":"CLEANED"}'
}

op_execute_bundle() {
  # R2-05/06/07: execute from a single unique bundle tar. The tar was uploaded to
  # deploy/tmp/incoming/<target>-<nonce>/; SHA is verified, then unpacking + all formal
  # changes happen under the deploy lock. request.json is read from the unpacked dir.
  # R2A-01 #6: --bundle is a BASE-relative path, resolved under BASE (no Windows/MSYS/abs-external).
  # R2B-01 #3: --bundle is a BASE-relative path, resolved under BASE (no Windows/MSYS/abs-external).
  BUNDLE_TAR="$(resolve_under "$BASE" "$BUNDLE_TAR")"
  [[ -f "$BUNDLE_TAR" ]] || die "BAD_REQUEST" "no bundle tar"
  [[ -n "$EXPECTED_BUNDLE_SHA" ]] || die "BAD_REQUEST" "no expected bundle sha"
  # canonicalize the incoming dir (resolve_under may yield an MSYS path)
  local incoming_dir; incoming_dir="$(cd "$(dirname "$BUNDLE_TAR")" 2>/dev/null && pwd -P 2>/dev/null)"
  local unpacked="$incoming_dir/unpacked"
  # ensure incoming_dir is precisely under INCOMING_ROOT and is this run's unique dir
  case "$incoming_dir" in
    "$INCOMING_ROOT"/*) ;;
    *) die "PATH_ESCAPE" "incoming dir outside precise root: $incoming_dir";;
  esac
  # cleanup this run's incoming on both success and failure (never touch other dirs)
  trap 'rm -rf -- "$incoming_dir"' EXIT
  acquire_lock
  # R2B-03: compute and compare the tar SHA UNDER the deploy lock (no TOCTOU window);
  # SHA MATCH must pass before any unpack. No release/re-acquire between SHA and unpack.
  local tar_sha; tar_sha="$(sha256_file "$BUNDLE_TAR")"
  if [[ "$tar_sha" != "$EXPECTED_BUNDLE_SHA" ]]; then
    die "BUNDLE_HASH_MISMATCH" "bundle sha $tar_sha != expected $EXPECTED_BUNDLE_SHA"
  fi
  mkdir -p "$unpacked"
  # unpack using the canonicalized incoming dir + basename (tar misparses C:/... paths)
  tar -xf "$incoming_dir/$(basename "$BUNDLE_TAR")" -C "$unpacked" || die "BUNDLE_UNPACK_FAILED"
  BUNDLE_ROOT="$unpacked"
  REQUEST="$unpacked/request.json"
  [[ -f "$REQUEST" ]] || die "BAD_REQUEST" "request.json missing in bundle"
  # lock already held above -> call the locked body directly (no re-acquire)
  op_execute_locked
}

guard_check() {
  [[ -n "$GUARD_PATH" ]] || usage
  assert_safe_destroy "$GUARD_PATH"
  echo "SAFE"
}

rm_check() {
  # Read-only: verify safe_rm_tree would REJECT this path (formal dir / escape).
  # Dies (DESTRUCTIVE_TARGET / PATH_ESCAPE) before any removal; prints SAFE only if allowed.
  [[ -n "$GUARD_PATH" ]] || usage
  safe_rm_tree "$GUARD_PATH"   # dies on formal/escape paths
  echo "SAFE"
}

case "$OPERATION" in
  plan)     op_plan ;;
  verify)   op_verify ;;
  execute)  op_execute ;;
  execute-bundle) op_execute_bundle ;;
  prepare-upload) op_prepare_upload ;;
  cleanup-upload) op_cleanup_upload ;;
  initialize) op_initialize ;;
  rollback) die "NOT_IMPLEMENTED_V1" "automatic database rollback is forbidden in V1" ;;
  guardcheck) guard_check ;;
  rmcheck)  rm_check ;;
  *)        usage ;;
esac
