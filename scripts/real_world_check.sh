#!/usr/bin/env bash
# Runs the built plugin, headless, over two real SQLAlchemy codebases (MLflow, CTFd).
#
# Two assertions, and BOTH must hold:
#   1. pristine checkout         -> 0 warnings   (false positives would kill adoption)
#   2. with 3 typos planted each -> 3 warnings   (proves the harness can actually fail)
#
# Assertion 2 exists because "no problems found" is indistinguishable from a broken harness: a stale
# .idea makes the inspector index 0 files, a JS bundle can crash it mid-run, and a failed launch writes
# no output at all. All three have happened here, so run() refuses to report 0 unless the inspector
# demonstrably produced results over a non-empty project.
set -euo pipefail

RIG="${RIG:-/home/yamu/side-hustle/.tools}"
PYCHARM="$RIG/pycharm-2026.2.3"
PLUGIN_ZIP="$(cd "$(dirname "$0")/.." && pwd)/build/distributions/mapper-guard-0.1.0.zip"
WORK="$RIG/real"
export PYCHARM_VM_OPTIONS="$RIG/pc.vmoptions"

[[ -x "$PYCHARM/bin/inspect.sh" ]] || { echo "PyCharm not found at $PYCHARM"; exit 2; }
[[ -f "$PLUGIN_ZIP" ]] || { echo "build the plugin first: ./gradlew buildPlugin"; exit 2; }

echo "== installing plugin into the headless IDE"
rm -rf "$RIG/pcconf/plugins/mapper-guard"
python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$PLUGIN_ZIP" "$RIG/pcconf/plugins"

# A pristine copy is kept per project and every phase restores from it, so a crashed run can never
# leave planted typos behind to poison the next one (that produced a bogus "3 warnings on clean code").
fetch() {  # $1=github repo  $2=local name  $3=subdir to keep
  local pristine="$WORK/.pristine_$2"
  if [[ ! -d "$pristine" ]]; then
    rm -rf "$WORK/.src_$2"
    git clone -q --depth 1 "https://github.com/$1.git" "$WORK/.src_$2"
    mkdir -p "$pristine"
    rsync -a --include='*/' --include='*.py' --exclude='*' "$WORK/.src_$2/$3" "$pristine/"
    rm -rf "$WORK/.src_$2"
  fi
}

# `touch` is load-bearing: PyCharm's file cache is keyed on (path, size, mtime), and rsync -a preserves
# mtimes, so a restored file was served from cache with the PREVIOUS phase's content. That made phase 1
# report planted typos on pristine code and phase 2 report nothing on planted code — one phase behind.
restore() {
  rm -rf "${WORK:?}/$1"; mkdir -p "$WORK/$1"
  rsync -a "$WORK/.pristine_$1/" "$WORK/$1/"
  find "$WORK/$1" -type f -exec touch {} +
}

# Scope (-d) matters: on a whole 500-file project PyCharm's batch runner silently drops this
# inspection's results (reproduced 2026-09-24: 0 warnings project-wide, 2 warnings with -d on the same
# planted file). The unit tests cover behaviour; this harness covers real code, so it runs scoped.
run() {  # $1=project  $2=subdir to inspect -> warning count, or ERROR:* if it did not demonstrably run
  local out="$RIG/out_$1" log="$RIG/pclog/idea.log"
  # The IDE's index (pcsys) is wiped for every run. Keeping it made results lag one phase behind
  # even after touching files: pristine code reported planted typos and vice versa.
  rm -rf "${WORK:?}/$1/.idea" "$out" "$RIG/pcsys"
  : > "$log" 2>/dev/null || true
  "$PYCHARM/bin/inspect.sh" "$WORK/$1" "$RIG/profile_sqla.xml" "$out" -v0 -format json -d "$WORK/$1/$2" >/dev/null 2>&1 || true
  if [[ ! -d "$out" || -z "$(ls -A "$out" 2>/dev/null)" ]]; then echo "ERROR:no-output"; return; fi
  if ! grep -qE "scanned files: [1-9]" "$log" 2>/dev/null; then echo "ERROR:indexed-nothing"; return; fi
  if [[ -n "${DEBUG:-}" ]]; then
    { echo "    debug[$1]: $(grep -oE 'scanned files: [0-9]+' "$log" | tail -1)"
      echo "    debug[$1]: planted markers present: $(grep -rl 'usrs.id\|Userz\|baned=False\|experiment_idd\|SqlExperimen\"\|run_uid=' "$WORK/$1" 2>/dev/null | wc -l) files"
      echo "    debug[$1]: json files: $(ls "$out" | wc -l)"; } >&2
  fi
  python3 - "$out/SqlaReference.json" <<'PY'
import json, os, sys
p = sys.argv[1]
print(len(json.load(open(p))["problems"]) if os.path.exists(p) else 0)
PY
}

mkdir -p "$WORK"
fetch CTFd/CTFd ctfd_py CTFd
fetch mlflow/mlflow mlflow_store mlflow/store

# project:subdir:expected-warnings-when-planted
SCOPES=("ctfd_py:CTFd/models:2" "ctfd_py:CTFd/api/v1:1" "mlflow_store:store/tracking:3")

fail=0
echo "== phase 1: pristine code (expect 0 warnings)"
for proj in ctfd_py mlflow_store; do restore "$proj"; done
for spec in "${SCOPES[@]}"; do
  IFS=: read -r proj dir _ <<< "$spec"
  n=$(run "$proj" "$dir")
  echo "  [$proj/$dir] $n warnings (want 0)"
  [[ "$n" == "0" ]] || fail=1
done

echo "== phase 2: 3 typos planted per project (expect 3 warnings each)"
restore ctfd_py
restore mlflow_store
python3 "$(dirname "$0")/plant_typos.py" "$WORK"

total=0
for spec in "${SCOPES[@]}"; do
  IFS=: read -r proj dir want <<< "$spec"
  n=$(run "$proj" "$dir")
  echo "  [$proj/$dir] $n warnings (want $want)"
  [[ "$n" == "$want" ]] || fail=1
  [[ "$n" =~ ^[0-9]+$ ]] && total=$((total + n))
done

for proj in ctfd_py mlflow_store; do restore "$proj"; done

echo
if [[ $fail == 0 ]]; then echo "PASS — 0 false positives on pristine code, $total/6 planted errors caught";
else echo "FAIL — see the counts above"; fi
exit $fail
