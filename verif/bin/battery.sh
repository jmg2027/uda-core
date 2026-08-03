#!/usr/bin/env bash
# Resource-aware battery runner: run several suite mains with bounded parallelism.
#
#   battery.sh [-j K] [-o LOGDIR] jobs.txt
#   battery.sh [-j K] [-o LOGDIR] "verif.suites.SmokeSuite" "verif.suites.SmokeSuite --config=minimal" ...
#
# jobs.txt holds one job per line (<MainClass> [args...]; blank lines and '#' comments skipped).
# K = concurrent jobs; default max(1, nproc/4) since one cold Verilator g++ build saturates ~4
# cores. Each job gets MAKEFLAGS -j(nproc/K) so concurrent cold builds split the cores instead of
# oversubscribing; ccache (env.sh OBJCACHE) makes repeat-config builds nearly free, so ordering
# does not matter. Per-job logs land in LOGDIR; a summary table prints at the end; exit is
# non-zero if any job exits non-zero.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"

NPROC=$(nproc 2>/dev/null || echo 4)
K=$(( NPROC / 4 )); [ "$K" -lt 1 ] && K=1
LOGDIR="${TMPDIR:-/tmp}/verif-battery-$$"
while getopts "j:o:" opt; do
  case "$opt" in
    j) K="$OPTARG" ;;
    o) LOGDIR="$OPTARG" ;;
    *) echo "usage: battery.sh [-j K] [-o LOGDIR] (jobs.txt | job ...)" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))

jobs=()
if [ $# -eq 1 ] && [ -f "$1" ]; then
  while IFS= read -r line; do
    line="${line%%#*}"
    [ -n "${line// }" ] && jobs+=("$line")
  done < "$1"
else
  jobs=("$@")
fi
[ ${#jobs[@]} -gt 0 ] || { echo "usage: battery.sh [-j K] [-o LOGDIR] (jobs.txt | job ...)" >&2; exit 2; }

[ "$K" -gt ${#jobs[@]} ] && K=${#jobs[@]}
PER_JOB_J=$(( NPROC / K )); [ "$PER_JOB_J" -lt 1 ] && PER_JOB_J=1
mkdir -p "$LOGDIR"
echo "[battery] ${#jobs[@]} jobs, $K concurrent, make -j$PER_JOB_J each, logs: $LOGDIR"

# set -u note: query possibly-empty arrays with ${arr[k]+x} guards; running counts in-flight jobs.
declare -A pid_of rc_of log_of
running=0
i=0
launch() {
  local idx="$1" job="$2"
  local slug; slug=$(echo "$job" | tr -c 'A-Za-z0-9.-' '_' | cut -c1-60)
  local log="$LOGDIR/$(printf '%02d' "$idx")-$slug.log"
  log_of[$idx]="$log"
  echo "[battery] start ($(date +%H:%M:%S)): $job"
  # shellcheck disable=SC2086
  MAKEFLAGS="-j$PER_JOB_J OPT_FAST=-O0 OPT_GLOBAL=-O0" "$HERE/run.sh" $job > "$log" 2>&1 &
  pid_of[$idx]=$!
  running=$((running + 1))
}
reap_one() {
  local idx
  # Poll (portable across bash versions without wait -n -p): find any finished child.
  while :; do
    for idx in "${!pid_of[@]}"; do
      if ! kill -0 "${pid_of[$idx]}" 2>/dev/null; then
        wait "${pid_of[$idx]}" 2>/dev/null; rc_of[$idx]=$?
        unset "pid_of[$idx]"
        running=$((running - 1))
        echo "[battery] done  ($(date +%H:%M:%S), rc=${rc_of[$idx]}): ${jobs[$idx]}"
        return
      fi
    done
    sleep 2
  done
}
while [ "$i" -lt ${#jobs[@]} ] || [ "$running" -gt 0 ]; do
  if [ "$i" -lt ${#jobs[@]} ] && [ "$running" -lt "$K" ]; then
    launch "$i" "${jobs[$i]}"; i=$((i + 1))
  else
    reap_one
  fi
done

echo
echo "[battery] summary:"
fails=0
for idx in $(seq 0 $(( ${#jobs[@]} - 1 ))); do
  rc=${rc_of[$idx]-?}
  [ "$rc" = "0" ] || fails=$((fails + 1))
  printf '  rc=%-3s %-70s %s\n' "$rc" "${jobs[$idx]}" "${log_of[$idx]-}"
done
[ "$fails" -eq 0 ] && echo "[battery] all green" || echo "[battery] $fails job(s) failed"
exit $(( fails > 0 ))
