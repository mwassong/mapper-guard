# Changelog — Mapper Guard

## [Unreleased]
- Fix #1: models declared with `__table__ = Table(...)` (hybrid mapping) no longer get false `filter_by()` /
  `back_populates` warnings on their columns; a `__table__` whose columns can't be seen makes the check stay quiet.

## 0.1.5 — 2026-11-04 (first public release)
- Marketplace "What's New" now describes the release instead of listing internal pre-release versions.
  No code changes from 0.1.4.

## 0.1.4 — 2026-11-03
Final review with new kinds of checks: a 7-project corpus sweep (Superset, Airflow, Redash, Flask-AppBuilder,
Indico, Lemur, FlaskBB - ~4,400 files never seen before) and mutation testing.
- `backref="x"` / `backref=backref("x")` attributes are known on the target class and its subclasses
  (Indico: 3 false alarms on `filter_by(survey=...)`).
- A relationship target imported anywhere in the project counts as existing even when its library isn't
  installed (Superset `relationship("Group")` from Flask-AppBuilder).
- Library tables: only tables a library actually DECLARES are accepted. 0.1.3 accepted any name that appeared in
  a library string, which hid the most common mistake (`ForeignKey("user.id")` for table `users`; "user" appears
  in 31 library files with only SQLAlchemy+Flask installed).
- Flask-SQLAlchemy's automatic table names (`UserRole(db.Model)` -> `user_role`, using its exact
  `camel_to_snake_case`) are known; Flask apps relying on them no longer get "Unknown table".
- Corpus found 2 real bugs in Netflix Lemur: `ForeignKey("policy.id")` to a table its migrations dropped.
- Tests 29 -> 37; mutation testing closes four gaps the suite had.

## 0.1.3 — 2026-11-03
Third review pass, plus the checks that had been missing:
- Tables declared only by an installed library (Flask-AppBuilder/Airflow `ab_user`) no longer flagged unknown.
- f-strings, implicitly concatenated and escaped strings are skipped (were misread, and warnings could land on
  the wrong characters).
- An unresolvable base class named `Base`/`Model` no longer counts as harmless; only SQLAlchemy's own bases and
  Flask's qualified `db.Model` do.
- Ctrl+click on `relationship("X")` works for imperatively mapped / `__table__` / nested classes.
- Performance: a word-index prefilter means only files that mention SQLAlchemy declarations are parsed;
  per-class results (columns, attributes, base-class resolution) are cached until the next change.
- No errors while the IDE is indexing (the new prefilter would have thrown; caught by an existing test).
- New `scripts/installed_check.sh`: fresh IDE profile, real interpreter with SQLAlchemy + Flask-SQLAlchemy and a
  library that declares its own table, then uninstall. Tests: 25 -> 29.

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
