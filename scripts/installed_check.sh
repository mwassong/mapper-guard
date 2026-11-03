#!/usr/bin/env bash
# The setup real users have, which the other checks don't cover:
#   * a configured interpreter with SQLAlchemy and Flask-SQLAlchemy INSTALLED (so calls resolve),
#   * a third-party library that declares its own table (`ab_user`) and a column-bearing mixin named `Model`,
#   * the plugin installed into a brand-new IDE profile, then uninstalled.
#
# Assertions:
#   1. clean profile + plugin   -> plugin loads; valid code gives 0 warnings
#   2. same, typos planted      -> every planted typo is reported (proves resolution didn't switch checks off)
#   3. plugin removed           -> IDE starts, plugin not loaded, no results, no plugin exceptions
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RIG="${RIG:-/home/yamu/side-hustle/.tools}"
PYCHARM="$RIG/pycharm-2026.2.3"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/build.gradle.kts")"
PLUGIN_ZIP="$ROOT/build/distributions/mapper-guard-$VERSION.zip"
WORK="$RIG/installed"
PROFILE="$WORK/profile"            # wiped every run: a genuinely clean profile
PROJ="$WORK/project"
WANT_PLANTED=6

[[ -x "$PYCHARM/bin/inspect.sh" ]] || { echo "PyCharm not found at $PYCHARM"; exit 2; }
[[ -f "$PLUGIN_ZIP" ]] || { echo "build first: ./gradlew buildPlugin"; exit 2; }

# ---------------------------------------------------------------- the project + a real interpreter
build_project() {
  rm -rf "$PROJ"; mkdir -p "$PROJ/app" "$WORK/fakelib/fakelib"
  cat > "$WORK/fakelib/pyproject.toml" <<'EOF'
[project]
name = "fakelib"
version = "1.0"
[build-system]
requires = ["setuptools"]
build-backend = "setuptools.build_meta"
EOF
  cat > "$WORK/fakelib/fakelib/__init__.py" <<'EOF'
"""Stands in for Flask-AppBuilder/Airflow: declares a table the app references but never subclasses."""
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class LibBase(DeclarativeBase):
    pass


class AbUser(LibBase):
    __tablename__ = "ab_user"
    id: Mapped[int] = mapped_column(primary_key=True)


class Model:
    """A mixin literally named Model that contributes columns."""
    id: Mapped[int] = mapped_column(primary_key=True)
    created_at: Mapped[int] = mapped_column()
EOF
  : > "$PROJ/app/__init__.py"
  cat > "$PROJ/app/util.py" <<'EOF'
def relationship(name):          # a project function that merely shares the name
    return name
EOF
  cat > "$PROJ/app/other.py" <<'EOF'
from app.util import relationship

x = relationship("NotAModel")
EOF
  cat > "$PROJ/app/models.py" <<'EOF'
from flask_sqlalchemy import SQLAlchemy
from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship

from fakelib import Model as LibModel


class Base(DeclarativeBase):
    pass


db = SQLAlchemy()


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(50))
    profile_id: Mapped[int] = mapped_column(ForeignKey("ab_user.id"))
    posts: Mapped[list["Post"]] = relationship(back_populates="author")


class Post(Base):
    __tablename__ = "posts"
    id: Mapped[int] = mapped_column(primary_key=True)
    author_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    author: Mapped["User"] = relationship(back_populates="posts")


class Order(LibModel, Base):
    __tablename__ = "orders"
    note: Mapped[str] = mapped_column()


class Ship(Base):
    __tablename__ = "ships"
    id: Mapped[int] = mapped_column(primary_key=True)
    order_id: Mapped[int] = mapped_column(ForeignKey("orders.id"))
    when: Mapped[int] = mapped_column(ForeignKey("orders.created_at"))


class Task(db.Model):
    __tablename__ = "tasks"
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("users.id"))
    user = db.relationship("User")


def queries(s: Session) -> None:
    s.query(User).filter_by(name="x")
    Task.query.filter_by(user_id=1)
    s.query(Order).filter_by(created_at=1, note="n")
EOF
  if [[ ! -x "$WORK/venv/bin/python" ]]; then
    uv venv -q "$WORK/venv"
    uv pip install -q --python "$WORK/venv/bin/python" "sqlalchemy>=2" flask-sqlalchemy "$WORK/fakelib"
  fi
  ln -sfn "$WORK/venv" "$PROJ/.venv"    # PyCharm auto-detects ./.venv as the project interpreter
}

plant() {  # content-matched, exactly once each; fails loudly if the project drifted
  python3 - "$PROJ/app/models.py" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
for old, new in [
    ('author_id: Mapped[int] = mapped_column(ForeignKey("users.id"))',
     'author_id: Mapped[int] = mapped_column(ForeignKey("userz.id"))'),          # unknown table
    ('db.relationship("User")', 'db.relationship("Usr")'),                       # unknown class (Flask)
    ('filter_by(name="x")', 'filter_by(nmae="x")'),                              # filter_by column
    ('db.ForeignKey("users.id")', 'db.ForeignKey("users.idd")'),                 # FK column via db.ForeignKey
    ('ForeignKey("orders.created_at")', 'ForeignKey("orders.created_att")'),     # column from RESOLVED library mixin
    ('filter_by(user_id=1)', 'filter_by(usr_id=1)'),                             # Flask Model.query.filter_by
]:
    assert s.count(old) == 1, ("planting failed", old)
    s = s.replace(old, new)
open(p, "w").write(s)
PY
}

# ---------------------------------------------------------------- run the IDE headless with a profile
run() {  # $1 = label -> prints warning count; also leaves $WORK/out and $PROFILE/log
  local opts="$WORK/vmoptions"
  printf -- '-Didea.config.path=%s/config\n-Didea.system.path=%s/system\n-Didea.log.path=%s/log\n-Xmx3g\n' \
    "$PROFILE" "$PROFILE" "$PROFILE" > "$opts"
  rm -rf "$WORK/out" "$PROJ/.idea" "$PROFILE/system" "$PROFILE/log"
  printf '<profile version="1.0"><option name="myName" value="mg"/>\n<inspection_tool class="SqlaReference" enabled="true" level="WARNING" enabled_by_default="true"/>\n<inspection_tool class="PyUnresolvedReferencesInspection" enabled="true" level="WARNING" enabled_by_default="true"/>\n</profile>\n' \
    > "$WORK/profile.xml"
  PYCHARM_VM_OPTIONS="$opts" "$PYCHARM/bin/inspect.sh" "$PROJ" "$WORK/profile.xml" "$WORK/out" -v0 -format json -d "$PROJ/app" \
    >/dev/null 2>&1 || true
  [[ -d "$WORK/out" && -n "$(ls -A "$WORK/out" 2>/dev/null)" ]] || { echo "ERROR:no-output"; return; }
  python3 - "$WORK/out/SqlaReference.json" <<'PY'
import json, os, sys
p = sys.argv[1]
# Distinct (file, offset, message): the batch runner occasionally emits an identical entry twice
# (seen once, not reproducible; the editor reports it once - see testEachProblemReportedExactlyOnce).
probs = json.load(open(p))["problems"] if os.path.exists(p) else []
print(len({(q.get("file"), q.get("offset"), q.get("description")) for q in probs}))
PY
}

log() { cat "$PROFILE"/log/idea.log 2>/dev/null; }

fail=0
build_project
echo "== fresh profile, plugin installed from the zip"
rm -rf "$PROFILE"; mkdir -p "$PROFILE/config/plugins"
python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$PLUGIN_ZIP" "$PROFILE/config/plugins"

n=$(run pristine)
loaded=$(log | grep -c "Loaded custom plugins: Mapper Guard" || true)
interp=$(log | grep -cE "PythonSdkUpdater.*$WORK/venv|SDK.*\.venv" || true)
echo "  plugin loaded: $loaded | interpreter configured: $interp | valid code: $n warnings (want 0)"
[[ "$loaded" -ge 1 && "$interp" -ge 1 && "$n" == "0" ]] || fail=1
[[ "$n" == "0" ]] || cat "$WORK/out/SqlaReference.json" 2>/dev/null | python3 -c "import json,sys;[print('   ',p.get('line'),p.get('description')) for p in json.load(sys.stdin)['problems']]" || true

plant
n=$(run planted)
echo "  planted typos: $n warnings (want $WANT_PLANTED)"
[[ "$n" == "$WANT_PLANTED" ]] || fail=1
[[ -f "$WORK/out/SqlaReference.json" ]] && python3 -c "import json,sys;[print('   ',p.get('line'),p.get('description')) for p in json.load(open(sys.argv[1]))['problems']]" "$WORK/out/SqlaReference.json"

echo "== uninstall (remove the plugin from the profile)"
rm -rf "$PROFILE/config/plugins/mapper-guard"
n=$(run uninstalled)
loaded=$(log | grep -c "Loaded custom plugins: Mapper Guard" || true)
errs=$(log | grep -ciE "sqlatoolkit|mapper.guard.*(exception|error)" || true)
echo "  plugin loaded: $loaded (want 0) | plugin errors in log: $errs (want 0) | results: $n (want 0)"
[[ "$loaded" == "0" && "$errs" == "0" && "$n" == "0" ]] || fail=1

echo
if [[ $fail == 0 ]]; then echo "PASS — clean install, real interpreter, library tables, uninstall"; else echo "FAIL"; fi
exit $fail
