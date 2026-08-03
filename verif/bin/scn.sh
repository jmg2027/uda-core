#!/usr/bin/env bash
# AI harness CLI. Subcommands map to the pre-compiled verif.ai entrypoints (no recompile).
#   scn.sh run      <file.scn> [--json] [--config=<name>] [--daemon]
#   scn.sh batch    <a.scn> <b.scn> ... [--json] [--config=<name>]
#   scn.sh gate     <scn> [control.scn] [--json] [--config=<name>]
#   scn.sh trace    <scn> [--taps=pc,instRaw,...] [--cycles=N] [--json] [--config=<name>]
#   scn.sh serve    [start|stop|status] [--config=<name>]
#   scn.sh describe
# Build once with verif/bin/build.sh; thereafter authoring .scn files needs no rebuild.
#
# The daemon (`serve start`, then `run ... --daemon`) keeps one compiled DUT resident and answers
# a .scn in seconds instead of a fresh multi-minute compile per invocation. One daemon per config
# name (default: "default"). --daemon output is always JSON.
#
# Rebuild-line status: DUT-facing commands answer {"error":"harness-not-ready", ...} (exit 3)
# while CoreTop is a spec shell; describe/parse-level behavior works today.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
VERIF="$(cd "$HERE/.." && pwd)"
sub="$1"; shift || true

tag_of() { # config tag = the --config=<name> value, default "default"
  for a in "$@"; do case "$a" in --config=*) echo "${a#--config=}"; return ;; esac; done
  echo default
}
SPOOL_BASE="${VERIF_DAEMON_SPOOL:-/tmp/verif-daemon}"

classes_fp() { find "$VERIF/out/classes" -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1; }

daemon_alive() { # $1=spool -> 0 if the daemon pid is alive
  [ -f "$1/meta" ] || return 1
  local pid; pid=$(awk '{print $2}' "$1/meta")
  kill -0 "$pid" 2>/dev/null
}

case "$sub" in
  serve)
    action="start"
    args=()
    for a in "$@"; do case "$a" in start|stop|status) action="$a" ;; *) args+=("$a") ;; esac; done
    tag=$(tag_of "${args[@]:-}")
    SPOOL="$SPOOL_BASE/$tag"
    case "$action" in
      start)
        if daemon_alive "$SPOOL"; then echo "[serve] already running (config=$tag, spool=$SPOOL)"; exit 0; fi
        rm -rf "$SPOOL"; mkdir -p "$SPOOL"
        nohup "$HERE/run.sh" verif.ai.Serve "${args[@]:-}" > "$SPOOL/daemon.log" 2>&1 &
        echo "[serve] starting config=$tag (pid $!); first request is served after the one-time DUT compile"
        echo "[serve] log: $SPOOL/daemon.log   ready marker: $SPOOL/ready"
        ;;
      stop)
        if ! daemon_alive "$SPOOL"; then echo "[serve] not running (config=$tag)"; exit 0; fi
        touch "$SPOOL/stop"
        pid=$(awk '{print $2}' "$SPOOL/meta" 2>/dev/null || true)
        for _ in $(seq 1 100); do kill -0 "$pid" 2>/dev/null || { echo "[serve] stopped"; exit 0; }; sleep 0.2; done
        echo "[serve] still shutting down (pid $pid)"; exit 1
        ;;
      status)
        if daemon_alive "$SPOOL"; then
          state=$([ -f "$SPOOL/ready" ] && echo ready || echo compiling)
          echo "[serve] running (config=$tag, $state, meta: $(cat "$SPOOL/meta"))"
        else echo "[serve] not running (config=$tag)"; fi
        ;;
    esac
    exit 0 ;;
  run)      cls=verif.ai.RunSpec ;;
  batch)    cls=verif.ai.RunBatch ;;
  gate)     cls=verif.ai.Gate ;;
  trace)    cls=verif.ai.Trace ;;
  describe) cls=verif.ai.Describe ;;
  *) echo "usage: scn.sh <run|batch|gate|trace|serve|describe> [args...]" >&2; exit 2 ;;
esac

# Daemon client path: submit the .scn to the resident DUT and wait for its JSON.
if [ "$cls" = "verif.ai.RunSpec" ]; then
  case " $* " in *" --daemon "*)
    tag=$(tag_of "$@")
    SPOOL="$SPOOL_BASE/$tag"
    file=""
    for a in "$@"; do case "$a" in --*) ;; *) file="$a" ;; esac; done
    [ -n "$file" ] && [ -f "$file" ] || { echo "scn.sh: --daemon needs a .scn file" >&2; exit 2; }
    daemon_alive "$SPOOL" || { echo "scn.sh: no daemon for config=$tag - start one: scn.sh serve start $([ "$tag" != default ] && echo --config=$tag)" >&2; exit 2; }
    dfp=$(awk '{print $3}' "$SPOOL/meta"); cfp=$(classes_fp)
    if [ -n "$cfp" ] && [ "$cfp" -gt "$dfp" ]; then
      echo "scn.sh: daemon is stale (harness rebuilt since serve start) - scn.sh serve stop && scn.sh serve start" >&2; exit 2
    fi
    for _ in $(seq 1 $(( ${VERIF_DAEMON_TIMEOUT:-300} * 2 ))); do
      [ -f "$SPOOL/ready" ] && break; sleep 0.5
    done
    [ -f "$SPOOL/ready" ] || { echo "scn.sh: daemon never became ready (see $SPOOL/daemon.log)" >&2; exit 2; }
    id="$$-$(date +%s%N)"
    cp "$file" "$SPOOL/req/$id.tmp" && mv "$SPOOL/req/$id.tmp" "$SPOOL/req/$id.scn"
    for _ in $(seq 1 $(( ${VERIF_DAEMON_TIMEOUT:-300} * 10 ))); do
      if [ -f "$SPOOL/resp/$id.json" ]; then cat "$SPOOL/resp/$id.json"; echo; rm -f "$SPOOL/resp/$id.json"; exit 0; fi
      daemon_alive "$SPOOL" || { echo "scn.sh: daemon died mid-request (see $SPOOL/daemon.log)" >&2; exit 2; }
      sleep 0.1
    done
    echo "scn.sh: daemon response timed out (see $SPOOL/daemon.log)" >&2; exit 2
  esac
fi

exec "$HERE/run.sh" "$cls" "$@"
