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

## Verification (every release)
- **Unit tests** (`./gradlew test`): 29 tests. Every rule has "must flag" and "must NOT flag" cases, plus
  navigation, completion, exactly-once reporting and indexing-time safety.
- **Real code** (`scripts/real_world_check.sh`): MLflow and CTFd, headless PyCharm. 0 warnings on pristine
  code, and 6/6 planted typos caught.
- **Real install** (`scripts/installed_check.sh`): brand-new IDE profile, an interpreter with SQLAlchemy and
  Flask-SQLAlchemy installed, a library that declares its own table; then uninstall.
- **JetBrains Plugin Verifier** and **ktlint** on every push (GitHub Actions).

## Build
```
JAVA_HOME=<jdk21> ./gradlew buildPlugin      # -> build/distributions/mapper-guard-<ver>.zip
```
Builds against a local PyCharm (`PYCHARM_HOME`, default: the side-hustle test rig).
