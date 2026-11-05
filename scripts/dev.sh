#!/usr/bin/env bash
#
# Bring the local stack up for client work, and tear it down cleanly on Ctrl+C.
#
#   ./scripts/dev.sh          backend + Mailhog + adb reverse, then stream the backend log
#   ./scripts/dev.sh --down   stop Mailhog and drop the adb forwards
#
# The backend runs natively rather than in Docker so `tsx watch` reloads on edit;
# Mailhog runs in Docker because there is no reason to install an SMTP catcher on
# the host. Everything else here exists because of something that actually went
# wrong once — see the comments.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"

PORT="${PORT:-8080}"
MAILHOG_UI=8025

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# --- adb -----------------------------------------------------------------
# Every attached device gets the forward. `adb reverse` with more than one
# device attached fails with "more than one device/emulator" unless each is
# named with -s, which is the whole reason this loop exists rather than a
# single command.
adb_devices() {
  command -v adb >/dev/null 2>&1 || return 0
  adb devices | awk 'NR>1 && $2=="device" {print $1}'
}

reverse_all() {
  command -v adb >/dev/null 2>&1 || { warn "adb not on PATH — skipping port forward"; return 0; }
  local found=0
  while read -r serial; do
    [ -z "$serial" ] && continue
    found=1
    if adb -s "$serial" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
      ok "adb reverse on $serial"
    else
      warn "adb reverse failed on $serial"
    fi
  done < <(adb_devices)
  [ "$found" -eq 0 ] && warn "no devices attached — run this again after starting one"
  return 0
}

unreverse_all() {
  command -v adb >/dev/null 2>&1 || return 0
  while read -r serial; do
    [ -z "$serial" ] && continue
    adb -s "$serial" reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
  done < <(adb_devices)
}

# --- teardown ------------------------------------------------------------
if [ "${1:-}" = "--down" ]; then
  bold "Stopping"
  unreverse_all && ok "adb forwards removed"
  docker compose down >/dev/null 2>&1 && ok "Mailhog stopped" || warn "compose down skipped (daemon not running?)"
  warn "Colima left running — stop it with: colima stop"
  exit 0
fi

# --- docker daemon -------------------------------------------------------
# This machine has no Docker Desktop; the daemon comes from Colima, and a
# stopped VM shows up as the confusing "is the daemon running?" socket error.
bold "Docker"
command -v docker >/dev/null 2>&1 || die "docker CLI not found (brew install docker)"
if ! docker info >/dev/null 2>&1; then
  if command -v colima >/dev/null 2>&1; then
    warn "daemon not responding — starting Colima (can take a minute)"
    colima start
  else
    die "no Docker daemon, and Colima is not installed"
  fi
fi
ok "daemon up ($(docker context show 2>/dev/null))"

# --- env -----------------------------------------------------------------
[ -f "$ROOT/.env" ] || { cp "$ROOT/.env.example" "$ROOT/.env"; warn ".env created from .env.example"; }

# --- mailhog -------------------------------------------------------------
bold "Mailhog"
docker compose up -d mailhog >/dev/null 2>&1
for _ in $(seq 1 30); do
  curl -sf -o /dev/null --max-time 2 "http://localhost:$MAILHOG_UI/" && break
  sleep 1
done
curl -sf -o /dev/null --max-time 2 "http://localhost:$MAILHOG_UI/" \
  && ok "inbox at http://localhost:$MAILHOG_UI" \
  || warn "inbox not answering yet (amd64 image under emulation is slow to boot)"

# --- backend -------------------------------------------------------------
bold "Backend"

# Reuse an already-running backend rather than racing it for the port. Without
# this the script's own start fails on EADDRINUSE and the error points at the
# port instead of at the other terminal that owns it.
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  warn "port $PORT already in use — leaving it alone, assuming it is the backend"
  BACKEND_PID=""
else
  # nvm is a shell function, not a binary, so it has to be sourced before
  # `nvm use` means anything inside a script.
  if [ -s "${NVM_DIR:-$HOME/.nvm}/nvm.sh" ]; then
    # shellcheck disable=SC1091
    . "${NVM_DIR:-$HOME/.nvm}/nvm.sh"
    # `nvm use` reads .nvmrc from the working directory, and this repo keeps it
    # in backend/ rather than at the root — so this has to happen from there,
    # and in this shell rather than a subshell, or the PATH change is discarded.
    cd "$ROOT/backend"
    nvm use >/dev/null 2>&1 || true
    cd "$ROOT"
  fi

  # Stop rather than warn. The failure this prevents is `tsx watch` dying on
  # "bad option: --env-file-if-exists", which names a flag instead of naming the
  # Node version that lacks it — a wrong-version error should say so.
  REQUIRED=$(tr -d '[:space:]' < "$ROOT/backend/.nvmrc" 2>/dev/null || echo 22)
  ACTUAL=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
  [ "$ACTUAL" -ge "$REQUIRED" ] 2>/dev/null \
    || die "backend needs Node $REQUIRED+, found $(node --version 2>/dev/null || echo 'no node'). Try: nvm install $REQUIRED"
  ok "node $(node --version)"
  [ -d "$ROOT/backend/node_modules" ] || { warn "installing dependencies"; (cd "$ROOT/backend" && npm ci); }

  # Job control, so this child gets a process group of its own. Without it the
  # job shares the script's group, and `kill $BACKEND_PID` reaches only the
  # subshell — npm and the node process under it survive, orphaned, still
  # holding the port. Ctrl+C would then appear to work and leave EADDRINUSE
  # behind for the next run.
  set -m
  (cd "$ROOT/backend" && npm run dev) &
  BACKEND_PID=$!
  set +m
fi

# --- forwards ------------------------------------------------------------
bold "Devices"
reverse_all

# --- wait for health -----------------------------------------------------
bold "Health"
HEALTH=""
for _ in $(seq 1 40); do
  HEALTH=$(curl -s --max-time 2 "http://localhost:$PORT/health" 2>/dev/null || true)
  [ -n "$HEALTH" ] && break
  sleep 1
done

if [ -z "$HEALTH" ]; then
  warn "backend did not answer on :$PORT — see the log above"
else
  # Report each dependency rather than just "ok": /health answers 200 even when
  # one is degraded, so the body is the part that matters.
  echo "$HEALTH" | grep -q '"upstream":"reachable"' && ok "upstream reachable" || warn "upstream unreachable (Fake Store down or rate-limiting)"
  echo "$HEALTH" | grep -q '"smtp":"reachable"'     && ok "smtp reachable"     || warn "smtp unreachable — Mailhog may still be booting"
fi

cleanup() {
  echo
  bold "Stopping"
  if [ -n "${BACKEND_PID:-}" ]; then
    # Negative PID signals the whole process group. TERM rather than KILL so the
    # service runs its own shutdown — it drains in-flight requests and logs
    # "shutdown complete", which is worth preserving as a signal that it was
    # stopped cleanly rather than cut off.
    kill -TERM -"$BACKEND_PID" 2>/dev/null || kill -TERM "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  unreverse_all
  # Mailhog is deliberately left running: it holds the enquiry emails you were
  # just looking at, and killing it would throw them away mid-debug.
  warn "Mailhog left up — ./scripts/dev.sh --down to stop it"
  exit 0
}

echo
if [ -n "${BACKEND_PID:-}" ]; then
  # This script owns the backend, so it stays in the foreground and its log is
  # what you watch. Ctrl+C stops the backend and drops the forwards.
  bold "Ready — Ctrl+C to stop"
  trap cleanup INT TERM
  wait "$BACKEND_PID"
else
  # The backend belongs to another terminal. Exiting here leaves it and the
  # forwards in place, which is the point — this run was setup, not ownership.
  bold "Ready — backend was already running, so it and the forwards stay up"
fi
