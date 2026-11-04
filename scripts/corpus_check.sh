#!/usr/bin/env bash
# Precision at scale: run the built plugin over large, unrelated, real SQLAlchemy codebases it has never seen.
#
# For each project:
#   * every warning on its UNMODIFIED code is printed for a human to classify (false alarm vs. real bug);
#   * a control file with one deliberate mistake is added -- if that isn't reported, the run proved nothing;
#   * the IDE log is scanned for exceptions raised from this plugin;
#   * wall time is recorded (pass BASELINE=1 to also time the same run without the plugin).
#
# Usage: scripts/corpus_check.sh [name ...]       (default: all projects below)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RIG="${RIG:-/home/yamu/side-hustle/.tools}"
PYCHARM="$RIG/pycharm-2026.2.3"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/build.gradle.kts")"
PLUGIN_ZIP="$ROOT/build/distributions/mapper-guard-$VERSION.zip"
WORK="$RIG/corpus"
PROFILE="$WORK/profile"
REPORT="$WORK/report.tsv"

# name|github repo|python package subdir(s), space separated (package paths kept so absolute imports resolve)
PROJECTS=(
  "superset|apache/superset|superset"
  "airflow|apache/airflow|airflow-core/src/airflow providers/fab/src/airflow"
  "redash|getredash/redash|redash"
  "fab|dpgaspar/Flask-AppBuilder|flask_appbuilder"
  "indico|indico/indico|indico"
  "lemur|Netflix/lemur|lemur"
  "flaskbb|flaskbb/flaskbb|flaskbb"
)

mkdir -p "$WORK"
[[ -f "$PLUGIN_ZIP" ]] || { echo "build first: ./gradlew buildPlugin"; exit 2; }

control_file() {
  cat > "$1/mapperguard_control.py" <<'EOF'
# Control for scripts/corpus_check.sh: exactly one deliberate mistake, which the plugin must report.
from sqlalchemy import Column, ForeignKey, Integer
from sqlalchemy.orm import declarative_base

_ControlBase = declarative_base()


class MapperGuardControl(_ControlBase):
    __tablename__ = "mapperguard_control"
    id = Column(Integer, primary_key=True)
    broken = Column(Integer, ForeignKey("mapperguard_control.no_such_column"))
EOF
}

prepare() {  # $1 name $2 repo $3 subdirs -> project dir with only .py files under their package paths
  local name=$1 repo=$2 subs=$3 src="$WORK/.src_$1" dst="$WORK/$1"
  [[ -d "$src" ]] || git clone -q --depth 1 "https://github.com/$repo.git" "$src" || return 1
  rm -rf "$dst"; mkdir -p "$dst"
  for sub in $subs; do
    [[ -d "$src/$sub" ]] || { echo "  (skip missing $sub)"; continue; }
    (cd "$src" && rsync -a --relative --include='*/' --include='*.py' --exclude='*' "./$sub" "$dst/")
  done
  control_file "$dst"
}

install_plugin() {  # fresh profile each project: no state leaks between runs
  rm -rf "$PROFILE"; mkdir -p "$PROFILE/config/plugins"
  [[ "${1:-yes}" == yes ]] && python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$PLUGIN_ZIP" "$PROFILE/config/plugins"
  printf -- '-Didea.config.path=%s/config\n-Didea.system.path=%s/system\n-Didea.log.path=%s/log\n-Xmx4g\n' \
    "$PROFILE" "$PROFILE" "$PROFILE" > "$WORK/vmoptions"
  printf '<profile version="1.0"><option name="myName" value="mg"/>\n<inspection_tool class="SqlaReference" enabled="true" level="WARNING" enabled_by_default="true"/>\n</profile>\n' > "$WORK/profile.xml"
}

inspect() {  # $1 project dir $2 out dir -> prints elapsed seconds
  rm -rf "$1/.idea" "$2"
  local t0=$SECONDS
  PYCHARM_VM_OPTIONS="$WORK/vmoptions" timeout 3600 "$PYCHARM/bin/inspect.sh" "$1" "$WORK/profile.xml" "$2" -v0 -format json \
    >/dev/null 2>&1
  echo $((SECONDS - t0))
}

printf 'project\tfile\tline\tmessage\n' > "$REPORT"
names=("$@")
fail=0
for spec in "${PROJECTS[@]}"; do
  IFS='|' read -r name repo subs <<< "$spec"
  if [[ ${#names[@]} -gt 0 && ! " ${names[*]} " =~ " $name " ]]; then continue; fi
  echo "== $name ($repo)"
  prepare "$name" "$repo" "$subs" || { echo "  clone failed"; fail=1; continue; }
  nfiles=$(find "$WORK/$name" -name '*.py' | wc -l)
  install_plugin yes
  secs=$(inspect "$WORK/$name" "$WORK/out_$name")
  python3 - "$WORK/out_$name/SqlaReference.json" "$name" "$REPORT" <<'PY'
import json, os, sys
p, name, report = sys.argv[1:4]
probs = json.load(open(p))["problems"] if os.path.exists(p) else []
seen, control, other = set(), 0, []
for q in probs:
    key = (q.get("file"), q.get("offset"), q.get("description"))
    if key in seen:
        continue
    seen.add(key)
    f = q["file"].replace("file://$PROJECT_DIR$/", "")
    if f == "mapperguard_control.py":
        control += 1
    else:
        other.append((f, q.get("line"), q.get("description")))
with open(report, "a") as r:
    for f, line, d in other:
        r.write(f"{name}\t{f}\t{line}\t{d}\n")
print(f"  control caught: {'YES' if control == 1 else 'NO (' + str(control) + ')'} | warnings on project code: {len(other)}")
for f, line, d in other[:15]:
    print(f"    {f}:{line}  {d}")
PY
  excs=$(grep -E "SEVERE|ERROR" "$PROFILE/log/idea.log" 2>/dev/null | grep -ciE "sqlatoolkit|mapper.?guard" || true)
  echo "  python files: $nfiles | time with plugin: ${secs}s | plugin exceptions in log: $excs"
  [[ "$excs" == "0" ]] || { grep -B2 -A12 -iE "sqlatoolkit" "$PROFILE/log/idea.log" | head -40; fail=1; }
  if [[ "${BASELINE:-0}" == 1 ]]; then
    install_plugin no
    base=$(inspect "$WORK/$name" "$WORK/out_base_$name")
    echo "  time WITHOUT plugin: ${base}s"
  fi
done
echo; echo "all project-code warnings: $REPORT"
exit $fail
