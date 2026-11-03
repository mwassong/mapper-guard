# Changelog — Mapper Guard

## 0.1.2 — 2026-11-03
Second review pass over the 0.1.1 rewrite (each fix has a regression test that failed first):
- Columns built by helper functions are recognised: `Table(..., *timestamps())` and `id = uuid_pk()` no longer
  make a correct ForeignKey look broken.
- Another library's `Table` class (rich, prettytable...) with a computed first argument no longer switches off
  "Unknown table" for the whole project. Detection is now syntactic, so cached per-file facts no longer
  depend on other files or the interpreter.
- Checked and NOT a bug: references during indexing (dumb mode) - kept a test that forces a rebuild while
  indexes are unavailable.
- Tests: 22 -> 25.

## 0.1.1 — 2026-11-03
Fixes from a full code review. All were false alarms on valid SQLAlchemy, which is the failure that makes
people uninstall a checker. When the plugin can't know something for sure, it now says nothing.
- Table names computed by `@declared_attr` (e.g. `cls.__name__.lower()`) no longer cause "Unknown table".
- Reflected tables (`autoload_with=...`, `extend_existing`) no longer report every column as missing.
- `relationship("X")` is only flagged when no class named X exists anywhere: imperative mapping, `__table__`
  classes and models defined inside functions are recognised.
- `secondary="schema.table"` is understood.
- Columns contributed by `@declared_attr` methods on mixins are columns.
- Models whose base class can't be resolved (e.g. a library mixin with no interpreter configured) are skipped
  instead of having their inherited columns reported missing.
- Performance: facts are cached per file, so an edit re-reads one file instead of the whole project; the
  project-wide scan is cancellable.
- Tests: 10 -> 22, including the first tests for Ctrl+click navigation and completion. ktlint added to CI.

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
