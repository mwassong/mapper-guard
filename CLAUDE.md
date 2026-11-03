# SQLAlchemy Toolkit — working rules

Inherits the account-wide quality bar in `~/.claude/CLAUDE.md`. Specific to this repo:

## The product promise
One thing, done properly: **SQLAlchemy string references that only break at runtime are caught while you
type.** A false positive in a linter-like plugin is worse than a missed error — it trains users to ignore
the warning, then uninstall. Precision comes before coverage, always.

## Before any release
```bash
JAVA_HOME=$(ls -d ~/.local/opt/jdk-21*) ./gradlew test verifyPlugin buildPlugin
```
1. **Unit tests green** (`src/test/kotlin`). Every rule has both a "must flag" and a "must NOT flag" case.
2. **`verifyPlugin` clean** — JetBrains' own compatibility check; it gates Marketplace approval.
3. **Real-codebase run**: the harness in `/home/yamu/side-hustle/.tools` runs the plugin headless over
   MLflow and CTFd. Required result: **0 warnings on unmodified code**, and with the typos planted by
   `plant_typos.sh`, **6/6 caught**. A run that reports nothing on both is a broken harness, not a pass —
   check `SqlaModel built: N classes` in `pclog/idea.log` and that `.idea/` was deleted first.
4. **Version bumped** in `build.gradle.kts` + `CHANGELOG.md` entry + git tag.

## Rules learned the hard way (do not regress)
- **Match by resolution, not by name.** A project's own `relationship()` function must not be flagged;
  `SqlaPsi.isSqlalchemyCall` resolves the callee. Unresolvable = assume SQLAlchemy, so the plugin still
  works before an interpreter is configured.
- **Duplicate model names are normal** (tests, multiple apps). A reference is valid if ANY candidate
  matches; navigation prefers the same file.
- **DB column names differ from attribute names** (`mapped_column("email", ...)`). Both resolve.
- **Never widen a check without adding a "must NOT flag" test** in the same commit.
- Inspection warnings carry the exact range *inside* the quotes, not the quotes themselves.

## Not built yet (deliberate)
`primaryjoin`/`order_by` string expressions, `backref()` strings, Alembic, async sessions, SQLModel.
Each needs its own precision work — shipping them half-right would violate the promise above.
