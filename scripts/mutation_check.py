"""Mutation testing: break one safeguard at a time and check the unit tests notice.

Usage: python3 scripts/mutation_check.py      (works on a throwaway copy; the repo is never modified)

Each entry disables one rule. "killed" = some test failed, which is what we want. A mutation marked
[integration-only] can't be reached by unit tests (it needs installed libraries); verify it instead by applying
it to a copy and running scripts/installed_check.sh, which must FAIL.
"""
import pathlib, subprocess, sys, os, glob, re, shutil, tempfile
repo = pathlib.Path(__file__).resolve().parent.parent
root = pathlib.Path(tempfile.mkdtemp(prefix="mapperguard-mut-"))
subprocess.run(["rsync", "-a", "--exclude", "build", "--exclude", ".gradle", "--exclude", ".intellijPlatform",
                f"{repo}/", f"{root}/"], check=True)
K = "src/main/kotlin/sqlatoolkit/"
M = [
 ("f-string/concat guard off", K+"SqlaPsi.kt", "if (elements.size != 1 || elements[0] is PyFormattedStringElement) return false", "if (false) return false"),
 ("resolution guard off (own relationship())", K+"SqlaPsi.kt", "if (!isSqlalchemyCall(call)) return null", ""),
 ("back_populates keyword typo", K+"SqlaPsi.kt", 'kw?.keyword == "back_populates"', 'kw?.keyword == "back_populate"'),
 ("FK schema parsing broken", K+"SqlaPsi.kt", "val tableStart = value.lastIndexOf('.', dot - 1) + 1", "val tableStart = 0"),
 ("Mapped[List[...]] unwrap broken", K+"SqlaPsi.kt", 'setOf("List", "list",', 'setOf("list",'),
 ("unresolved base treated as known", K+"SqlaModel.kt", "if (ref.reference.resolve() == null) return false", "if (false) return false"),
 ("reflected Table treated as complete", K+"SqlaModel.kt", "var known = call.arguments.none { it is PyKeywordArgument && it.keyword in REFLECTION_KWARGS }", "var known = true"),
 ("dynamic __tablename__ ignored", K+"SqlaModel.kt", "TableName.Dynamic -> dynamic = true", "TableName.Dynamic -> {}"),
 ("other libraries' Table accepted", K+"SqlaModel.kt", "if (positional.size < 2 || positional[1] is PyStringLiteralExpression) continue", "if (positional.size < 2) continue"),
 ("declared_attr columns dropped", K+"SqlaModel.kt", 'for (m in c.methods) if (hasDecorator(m, "declared_attr")) m.name?.let { out.putIfAbsent(it, m) }', ""),
 ("helper-built columns dropped", K+"SqlaModel.kt", '} else if (value is PyCallExpression && !isRelationshipCall(value) && !name.startsWith("__")) {', "} else if (false) {"),
 ("schema-qualified table lookup off", K+"SqlaModel.kt", "tables[name] ?: tables[name.substringAfterLast('.')].orEmpty()", "tables[name].orEmpty()"),
 ("library-declared tables ignored [integration-only]", K+"SqlaReferenceInspection.kt", "return name.substringAfterLast('.') !in SqlaModel.libraryTables(context.project).names", "return true"),
 ("Flask automatic table names off", K+"SqlaModel.kt", "val flask = isFlaskModel(cls)", "val flask = false"),
 ("backref attributes ignored", K+"SqlaModel.kt", "out.addAll(get(cls.project).backrefsOf(cls))", ""),
 ("imported-name check off", K+"SqlaReferenceInspection.kt", "return importedInProject(simple, context)", "return false"),
 ("bare 'Model' treated as harmless", K+"SqlaModel.kt", '(ref.referencedName == "Model" && ref.qualifier != null)', '(ref.referencedName == "Model")'),
 ("filter_by check disabled", K+"SqlaReferenceInspection.kt", "if (attrs.none { name in it }) {", "if (false) {"),
 ("tablesComplete gate removed", K+"SqlaReferenceInspection.kt", "if (!model.tablesComplete || model.tableDeclarations(name).isNotEmpty()) return false", "if (model.tableDeclarations(name).isNotEmpty()) return false"),
 ("join() guard removed", K+"SqlaReferenceInspection.kt", '"join", "outerjoin",', '"outerjoin",'),
 ("library/nested class lookup off", K+"SqlaReferenceInspection.kt", "if (PyClassNameIndex.find(simple, context.project, true).isNotEmpty()) return true", ""),
]
env = dict(os.environ, JAVA_HOME=glob.glob(os.path.expanduser("~/.local/opt/jdk-21*"))[0])
results = []
for name, f, old, new in M:
    p = root / f; orig = p.read_text()
    if orig.count(old) != 1:
        results.append((name, "SKIP (pattern not found exactly once)")); continue
    p.write_text(orig.replace(old, new, 1))
    r = subprocess.run(["./gradlew", "test", "-q"], cwd=root, env=env, capture_output=True, text=True)
    p.write_text(orig)
    out = r.stdout + r.stderr
    if r.returncode == 0:
        results.append((name, "SURVIVED"))
    elif "tests completed" in out or "FAILED" in out:
        failed = re.findall(r"SqlaReferenceInspectionTest > (\w+) FAILED", out)
        results.append((name, "killed by " + (", ".join(failed[:2]) or "test failure")))
    else:
        results.append((name, "BUILD ERROR: " + out.strip().splitlines()[-1][:80] if out.strip() else "BUILD ERROR"))
    print(f"{results[-1][1][:9]:9} {name}", flush=True)
print()
killed = sum(1 for _, r in results if r.startswith("killed"))
print(f"mutation score: {killed}/{len(results)} killed")
for n, r in results:
    print(f"  {n}: {r}")

shutil.rmtree(root, ignore_errors=True)
