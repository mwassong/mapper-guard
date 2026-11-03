# Mapper Guard — catch broken SQLAlchemy string references in PyCharm

Catches broken SQLAlchemy string references while you type — the ones that otherwise only fail at app startup
(mapper configuration) or query time:

| Reference | Example caught |
|---|---|
| `relationship("Class")` | `relationship("Usre")` — unknown mapped class |
| `back_populates="attr"` | `back_populates="adreses"` — no such attribute on the target |
| `ForeignKey("table.column")` | `ForeignKey("usres.id")`, `ForeignKey("users.idd")` |
| `secondary="table"` | `secondary="user_rolez"` |
| `filter_by(col=...)` | `session.query(User).filter_by(nmae=...)`, `select(User).filter_by(...)`, Flask-SQLAlchemy `User.query.filter_by(...)` |

Plus Ctrl+click navigation and completion inside all of those strings.

## Verification (2026-09-21, PyCharm 2026.2.3, headless `inspect.sh`)
- Synthetic project: 10/10 planted errors caught, 0 false positives across annotation-only targets, dotted
  class names, mixin columns, `Table()` objects, DB-column-name overrides, self-references and duplicate model names.
- Real codebases, 3 typos planted in each: **MLflow** tracking store (58 models) 3/3, **CTFd** (48 models,
  Flask-SQLAlchemy) 3/3. **0 false positives** in either.

## Build
```
JAVA_HOME=<jdk21> ./gradlew buildPlugin      # -> build/distributions/mapper-guard-<ver>.zip
```
Builds against a local PyCharm (`PYCHARM_HOME`, default: the side-hustle test rig).
