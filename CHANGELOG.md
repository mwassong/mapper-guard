# Changelog — Mapper Guard

## 0.1.0 — 2026-11-03
First version. Catches SQLAlchemy string references that otherwise fail only at runtime:
- `relationship("Class")` — unknown mapped class
- `back_populates="attr"` — no such attribute on the target model
- `secondary="table"` — unknown association table
- `ForeignKey("table.column")` — unknown table or column (DB column names are understood)
- `filter_by(col=...)` — unknown column, including Flask-SQLAlchemy's `Model.query.filter_by(...)`

Plus Ctrl+click navigation and completion inside all of those strings.

Only calls that resolve into SQLAlchemy are checked, so a project's own `relationship()` is left alone.
Duplicate model names across files never produce false alarms.

Verified: 10 unit tests; JetBrains plugin verifier "Compatible" against PY-262.10968.92; headless run over
MLflow and CTFd — 0 warnings on pristine code, 6/6 planted errors caught.
