package sqlatoolkit

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression

/**
 * One declaration of a table: either a mapped class (columns derived lazily from the class, because they
 * can come from other files) or a core `Table(...)` call (columns listed inline, or `null` when they can't
 * be known statically, e.g. a reflected table).
 */
class SqlaTable(
    val name: String,
    val declaration: PsiElement,
    private val mappedClass: PyClass?,
    private val coreColumns: Map<String, PsiElement>?,
) {
    /** Column name -> declaring element, or null when the columns can't be enumerated. */
    fun columns(): Map<String, PsiElement>? = if (mappedClass != null) SqlaModel.declaredColumns(mappedClass) else coreColumns
}

/**
 * Project-wide view of SQLAlchemy declarations.
 *
 * Precision is the product: every place where the plugin cannot be sure (a dynamic `__tablename__`, a reflected
 * table, a base class it can't resolve) is recorded as *unknown*, and unknown means "stay quiet".
 *
 * Cost: facts are extracted per file and cached against that file alone, so an edit re-reads one file. The
 * project-level aggregate is rebuilt from those cached facts on any PSI change, which is a cheap merge.
 */
class SqlaModel private constructor(
    /** Class name -> project classes with that name that look like mapped models. */
    val classes: Map<String, List<PyClass>>,
    /** Table name (without schema) -> every declaration of that table. */
    val tables: Map<String, List<SqlaTable>>,
    /** False when some table's name can't be determined statically; "unknown table" is then never reported. */
    val tablesComplete: Boolean,
    /** Class name -> attribute names other classes add to it via `relationship(..., backref="x")`. */
    private val backrefsByTarget: Map<String, Set<String>> = emptyMap(),
    /** Backref names whose target class couldn't be determined: accepted on every class. */
    private val backrefsUnknownTarget: Set<String> = emptySet(),
) {
    /** Attributes `cls` (or an ancestor) gains from backrefs declared on other classes. */
    fun backrefsOf(cls: PyClass): Set<String> {
        val out = HashSet(backrefsUnknownTarget)
        for (c in listOf(cls) + cls.getAncestorClasses(null)) c.name?.let { backrefsByTarget[it]?.let(out::addAll) }
        return out
    }

    /** Per-file facts. Depends only on the file's own text, so it is safe to cache against the file. */
    private class FileFacts(
        val mappedClasses: List<PyClass>,
        val tables: List<SqlaTable>,
        /** Classes with no table name of their own: their name may be inherited, resolved at aggregate time. */
        val inheritsTableName: List<PyClass>,
        val hasDynamicTableNames: Boolean,
        /** (relationship target class name or null if unknown) -> backref attribute name it creates on that class. */
        val backrefs: List<Pair<String?, String>>,
    )

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlaModel::class.java)

        /**
         * SQLAlchemy's own bases add no user columns, so an unresolved reference to one is harmless. Deliberately
         * NOT the bare names `Base`/`Model`: an unresolvable library mixin can be called that and carry columns.
         * Flask-SQLAlchemy's `db.Model` is accepted only in its qualified form (see [isHarmlessBase]).
         */
        private val SQLALCHEMY_BASES = setOf(
            "object",
            "DeclarativeBase",
            "DeclarativeBaseNoMeta",
            "MappedAsDataclass",
            "AsyncAttrs",
            "SQLModel",
        )

        /** Words that must appear in a file for it to declare anything we index (word-index prefilter). */
        private val INDEXED_WORDS = listOf(
            "__tablename__",
            "__table__",
            "__mapper_args__",
            "Table",
            "Column",
            "mapped_column",
            "relationship",
            "column_property",
            "deferred",
        )
        private val COLUMN_CALLS = setOf("mapped_column", "Column", "column_property", "deferred")
        private val RELATIONSHIP_CALLS = setOf("relationship", "relation", "backref")
        private val ATTRIBUTE_DECORATORS = setOf("hybrid_property", "hybrid_method", "declared_attr", "column_property", "property")
        private val TABLE_NON_COLUMNS = setOf(
            "PrimaryKeyConstraint",
            "ForeignKeyConstraint",
            "UniqueConstraint",
            "CheckConstraint",
            "Index",
        )
        private val REFLECTION_KWARGS = setOf("autoload_with", "autoload", "extend_existing", "keep_existing")

        /** While the IDE is indexing, the word index isn't available: report "nothing known yet" (never cached). */
        private val EMPTY = SqlaModel(emptyMap(), emptyMap(), tablesComplete = false)

        fun get(project: Project): SqlaModel {
            if (DumbService.isDumb(project)) return EMPTY
            return CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(aggregate(project), PsiModificationTracker.getInstance(project))
            }
        }

        private fun factsOf(file: PyFile): FileFacts =
            CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(extract(file), file) }

        private fun aggregate(project: Project): SqlaModel {
            val classes = HashMap<String, MutableList<PyClass>>()
            val tables = HashMap<String, MutableList<SqlaTable>>()
            var complete = true
            val backrefsByTarget = HashMap<String, MutableSet<String>>()
            val backrefsUnknown = HashSet<String>()
            for (file in candidateFiles(project)) {
                ProgressManager.checkCanceled()
                val facts = factsOf(file)
                for (cls in facts.mappedClasses) cls.name?.let { classes.getOrPut(it) { mutableListOf() }.add(cls) }
                for (t in facts.tables) tables.getOrPut(t.name) { mutableListOf() }.add(t)
                if (facts.hasDynamicTableNames) complete = false
                for ((target, name) in facts.backrefs) {
                    if (target == null) backrefsUnknown.add(name) else backrefsByTarget.getOrPut(target) { HashSet() }.add(name)
                }
                // A table name inherited across files (e.g. from a library base) can only be resolved here.
                for (cls in facts.inheritsTableName) {
                    val flask = isFlaskModel(cls)
                    // Flask-SQLAlchemy names db.Model subclasses itself when __tablename__ isn't set. Registering the
                    // derived name even for single-table-inheritance children (which reuse the parent table) is
                    // harmless: an extra known name can only remove warnings, never add one.
                    if (flask) cls.name?.let {
                        tables.getOrPut(camelToSnake(it)) { mutableListOf() }.add(SqlaTable(camelToSnake(it), cls, cls, null))
                    }
                    when (val inherited = inheritedTableName(cls)) {
                        null -> if (!flask && !ancestorsKnown(cls)) complete = false
                        else -> tables.getOrPut(inherited) { mutableListOf() }.add(SqlaTable(inherited, cls, cls, null))
                    }
                }
            }
            LOG.debug("SqlaModel: ${classes.size} class names, ${tables.size} tables, complete=$complete")
            return SqlaModel(classes, tables, complete, backrefsByTarget, backrefsUnknown)
        }

        /**
         * Project Python files that can declare models or tables. The word index answers this without parsing,
         * so a large project only parses the handful of files that mention SQLAlchemy declarations.
         */
        private fun candidateFiles(project: Project): Collection<PyFile> {
            val scope = GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.projectScope(project), PythonFileType.INSTANCE)
            val helper = PsiSearchHelper.getInstance(project)
            val files = LinkedHashSet<PyFile>()
            for (word in INDEXED_WORDS) {
                helper.processAllFilesWithWord(word, scope, { f ->
                    (f as? PyFile)?.let(files::add)
                    true
                }, true)
            }
            return files
        }

        /** Table names that installed libraries DECLARE with a literal name (not merely mention). */
        class LibraryTables(val names: Set<String>)

        private val LIBRARY_TABLES = Key.create<CachedValue<LibraryTables>>("mapperguard.libraryTables")

        /**
         * Libraries can declare tables a project references but never subclasses (Flask-AppBuilder's `ab_user`,
         * Airflow's tables). These come from the same per-file extraction as project code, restricted by the word
         * index to library files that can declare a table at all. A name merely *appearing* in a library string is
         * not enough: "user" and "order" appear in dozens of library files, and accepting them would hide the most
         * common mistake there is, `ForeignKey("user.id")` for a table called `users`.
         * Recomputed when project roots (interpreter, installed packages) change.
         */
        fun libraryTables(project: Project): LibraryTables {
            if (DumbService.isDumb(project)) return LibraryTables(emptySet())
            return CachedValuesManager.getManager(project).getCachedValue(project, LIBRARY_TABLES, {
                CachedValueProvider.Result.create(computeLibraryTables(project), ProjectRootManager.getInstance(project))
            }, false)
        }

        private fun computeLibraryTables(project: Project): LibraryTables {
            val scope = GlobalSearchScope.getScopeRestrictedByFileTypes(ProjectScope.getLibrariesScope(project), PythonFileType.INSTANCE)
            val helper = PsiSearchHelper.getInstance(project)
            val files = LinkedHashSet<PyFile>()
            // A table is declared with __tablename__, or with Table(name, metadata, ...).
            helper.processAllFilesWithWord("__tablename__", scope, { f ->
                (f as? PyFile)?.let(files::add)
                true
            }, true)
            val withTable = HashSet<PyFile>()
            helper.processAllFilesWithWord("Table", scope, { f ->
                (f as? PyFile)?.let(withTable::add)
                true
            }, true)
            helper.processAllFilesWithWord("metadata", scope, { f ->
                (f as? PyFile)?.takeIf { it in withTable }?.let(files::add)
                true
            }, false)
            // Library internals call Table(variable, metadata) all the time (SQLAlchemy's own reflection code), so a
            // library's "dynamic names" flag means nothing here: only literal declarations count.
            val names = HashSet<String>()
            for (file in files) {
                ProgressManager.checkCanceled()
                factsOf(file).tables.mapTo(names) { it.name }
            }
            return LibraryTables(names)
        }

        private fun extract(file: PyFile): FileFacts {
            val mapped = ArrayList<PyClass>()
            val tables = ArrayList<SqlaTable>()
            val inherits = ArrayList<PyClass>()
            var dynamic = false
            for (cls in file.topLevelClasses) {
                val own = ownTableName(cls)
                when (own) {
                    is TableName.Literal -> tables.add(SqlaTable(own.name, cls, cls, null))
                    TableName.Dynamic -> dynamic = true
                    // `__table__ = Table("x", ...)` / `__table__ = some_table` are captured by the Table scan below;
                    // anything else (a computed table) means a table name we can't know.
                    TableName.None -> {
                        val t = cls.findClassAttribute("__table__", false, null)?.findAssignedValue()
                        if (t != null && t !is PyReferenceExpression && !(t is PyCallExpression && calleeName(t) == "Table")) dynamic = true
                    }
                }
                if (looksMapped(cls, own)) {
                    mapped.add(cls)
                    if (own == TableName.None) inherits.add(cls)
                }
            }
            // Core tables can be declared anywhere (module level, inside functions, in __table__ assignments).
            for (call in PsiTreeUtil.findChildrenOfType(file, PyCallExpression::class.java)) {
                if (calleeName(call) != "Table") continue
                val positional = call.arguments.filter { it !is PyKeywordArgument }
                // SQLAlchemy's signature is Table(name, metadata, *columns). Other libraries' Table classes (rich,
                // prettytable, ...) take strings there. Decided syntactically, NOT by resolving the call: these facts
                // are cached against this file alone, so they must not depend on other files or the interpreter.
                if (positional.size < 2 || positional[1] is PyStringLiteralExpression) continue
                val name = (positional[0] as? PyStringLiteralExpression)?.stringValue
                if (name == null) {
                    dynamic = true
                    continue
                }
                var known = call.arguments.none { it is PyKeywordArgument && it.keyword in REFLECTION_KWARGS }
                val cols = LinkedHashMap<String, PsiElement>()
                for (arg in positional.drop(2)) {
                    val c = arg as? PyCallExpression
                    when {
                        c != null && calleeName(c) == "Column" ->
                            (c.arguments.firstOrNull() as? PyStringLiteralExpression)?.let { cols[it.stringValue] = it }
                        c != null && calleeName(c) in TABLE_NON_COLUMNS -> {}
                        else -> known = false // *helper(), a variable, an unknown call: columns we can't see
                    }
                }
                tables.add(SqlaTable(name, call, null, if (known && cols.isNotEmpty()) cols else null))
            }
            // `relationship("Target", backref="x")` / `backref=backref("x", ...)` adds attribute x to Target at runtime.
            val backrefs = ArrayList<Pair<String?, String>>()
            for (call in PsiTreeUtil.findChildrenOfType(file, PyCallExpression::class.java)) {
                if (calleeName(call) !in setOf("relationship", "relation")) continue
                val arg = call.getKeywordArgument("backref") ?: continue
                val name = when (arg) {
                    is PyStringLiteralExpression -> arg.stringValue
                    is PyCallExpression -> (arg.arguments.firstOrNull() as? PyStringLiteralExpression)?.stringValue
                    else -> null
                } ?: continue
                backrefs.add(SqlaPsi.relationshipTargetName(call)?.substringAfterLast('.') to name)
            }
            return FileFacts(mapped, tables, inherits, dynamic, backrefs)
        }

        private sealed class TableName {
            class Literal(val name: String) : TableName()
            object Dynamic : TableName()
            object None : TableName()
        }

        /** The class's own `__tablename__`: a literal, something computed (declared_attr, expression), or absent. */
        private fun ownTableName(cls: PyClass): TableName {
            if (cls.findMethodByName("__tablename__", false, null) != null) return TableName.Dynamic
            val attr = cls.findClassAttribute("__tablename__", false, null) ?: return TableName.None
            val literal = (attr.findAssignedValue() as? PyStringLiteralExpression)?.stringValue
            return if (literal != null) TableName.Literal(literal) else TableName.Dynamic
        }

        /** Flask-SQLAlchemy's `camel_to_snake_case` (flask_sqlalchemy/model.py, 3.1.1), character for character. */
        fun camelToSnake(name: String): String =
            Regex("((?<=[a-z0-9])[A-Z]|(?!^)[A-Z](?=[a-z]))").replace(name) { "_" + it.value }.lowercase().trimStart('_')

        /** Subclasses (directly or not) Flask-SQLAlchemy's `db.Model`: a qualified `<something>.Model` base. */
        private fun isFlaskModel(cls: PyClass): Boolean = (listOf(cls) + cls.getAncestorClasses(null)).any { c ->
            c.superClassExpressions.any { e -> e is PyReferenceExpression && e.referencedName == "Model" && e.qualifier != null }
        }

        private fun inheritedTableName(cls: PyClass): String? =
            (cls.findClassAttribute("__tablename__", true, null)?.findAssignedValue() as? PyStringLiteralExpression)?.stringValue

        /** Mapped judged from the class's own body only, so per-file caching stays correct. */
        private fun looksMapped(cls: PyClass, own: TableName): Boolean = own != TableName.None ||
            cls.findClassAttribute("__table__", false, null) != null ||
            cls.findClassAttribute("__mapper_args__", false, null) != null ||
            cls.classAttributes.any { isColumnCall(it.findAssignedValue()) || isRelationshipCall(it.findAssignedValue()) }

        /**
         * False when some base class can't be resolved, so attributes it might contribute are unknown.
         * Cached per class until the next PSI change: it is asked for on every ForeignKey/filter_by/back_populates.
         */
        fun ancestorsKnown(cls: PyClass): Boolean = cached(cls, ANCESTORS_KNOWN) { computeAncestorsKnown(cls) }

        private fun computeAncestorsKnown(cls: PyClass): Boolean {
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                for (expr in c.superClassExpressions) {
                    val ref = expr as? PyReferenceExpression ?: continue
                    if (isHarmlessBase(ref)) continue
                    if (ref.reference.resolve() == null) return false
                }
            }
            return true
        }

        private fun isHarmlessBase(ref: PyReferenceExpression): Boolean =
            ref.referencedName in SQLALCHEMY_BASES || (ref.referencedName == "Model" && ref.qualifier != null) // db.Model

        /** Column name (attribute and DB name) -> element; null when the columns can't all be known. */
        fun declaredColumns(cls: PyClass): Map<String, PsiElement>? =
            cached(cls, DECLARED_COLUMNS) { Box(computeDeclaredColumns(cls)) }.value

        private fun computeDeclaredColumns(cls: PyClass): Map<String, PsiElement>? {
            if (!ancestorsKnown(cls)) return null
            val out = LinkedHashMap<String, PsiElement>()
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                if (reflectsColumns(c)) return null
                for (attr in c.classAttributes) {
                    val name = attr.name ?: continue
                    val value = attr.findAssignedValue()
                    if (isColumnCall(value)) {
                        out.putIfAbsent(name, attr)
                        // mapped_column("db_name", ...) / Column("db_name", ...) sets the DB column name
                        val dbName = ((value as PyCallExpression).arguments.firstOrNull() as? PyStringLiteralExpression)?.stringValue
                        if (dbName != null) out.putIfAbsent(dbName, attr)
                    } else if (value is PyCallExpression && !isRelationshipCall(value) && !name.startsWith("__")) {
                        out.putIfAbsent(name, attr) // `id = uuid_pk()`: a helper may build the column; can't tell, so count it
                    } else if (isMappedAnnotation(attr)) {
                        out.putIfAbsent(name, attr) // `x: Mapped[int]` with no assignment is still a column
                    }
                }
                // Mixins commonly contribute columns through @declared_attr methods.
                for (m in c.methods) if (hasDecorator(m, "declared_attr")) m.name?.let { out.putIfAbsent(it, m) }
            }
            return out
        }

        /** Attributes usable in filter_by()/back_populates; null when a base class can't be resolved. */
        fun attributesOf(cls: PyClass): Set<String>? = cached(cls, ATTRIBUTES) { Box(computeAttributes(cls)) }.value

        private fun computeAttributes(cls: PyClass): Set<String>? {
            if (!ancestorsKnown(cls)) return null
            val out = HashSet<String>()
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                c.classAttributes.mapNotNullTo(out) { it.name }
                // Hybrid mapping: every column of `__table__` becomes an attribute. Unknown columns -> unknown attributes.
                val table = c.findClassAttribute("__table__", false, null)
                if (table != null) out.addAll(hybridTableColumns(table.findAssignedValue()) ?: return null)
                c.methods.filter { m -> ATTRIBUTE_DECORATORS.any { hasDecorator(m, it) } }.mapNotNullTo(out) { it.name }
            }
            out.addAll(get(cls.project).backrefsOf(cls)) // attributes added to this class by backrefs elsewhere
            return out
        }

        /**
         * Attribute names `__table__ = Table("t", metadata, Column("a", ...), ...)` maps onto its class: each column's
         * `key=` if given, else its name. Null unless every column is a literal inline `Column(...)` (a variable, a
         * reflected table, `*helper()`, a computed name or key): those columns can't be known, so stay quiet.
         */
        private fun hybridTableColumns(value: PyExpression?): Set<String>? {
            val call = value as? PyCallExpression ?: return null
            if (calleeName(call) != "Table") return null
            if (call.arguments.any { it is PyKeywordArgument && it.keyword in REFLECTION_KWARGS }) return null
            val positional = call.arguments.filter { it !is PyKeywordArgument }
            val out = HashSet<String>()
            for (arg in positional.drop(2)) {
                val c = arg as? PyCallExpression ?: return null
                when (calleeName(c)) {
                    "Column" -> {
                        val key = c.getKeywordArgument("key")
                        val name = if (key != null) {
                            (key as? PyStringLiteralExpression)?.stringValue
                        } else {
                            (c.arguments.firstOrNull() as? PyStringLiteralExpression)?.stringValue
                        }
                        out.add(name ?: return null)
                    }
                    in TABLE_NON_COLUMNS -> {}
                    else -> return null
                }
            }
            return out
        }

        /** CachedValue results must be non-null, so nullable results travel in a box. */
        private class Box<T>(val value: T)

        private val ANCESTORS_KNOWN = Key.create<CachedValue<Boolean>>("mapperguard.ancestorsKnown")
        private val DECLARED_COLUMNS = Key.create<CachedValue<Box<Map<String, PsiElement>?>>>("mapperguard.declaredColumns")
        private val ATTRIBUTES = Key.create<CachedValue<Box<Set<String>?>>>("mapperguard.attributes")

        /** Per-class cache, invalidated on any PSI change (results depend on ancestors in other files). */
        private fun <T : Any> cached(cls: PyClass, key: Key<CachedValue<T>>, compute: () -> T): T =
            CachedValuesManager.getManager(cls.project).getCachedValue(cls, key, {
                CachedValueProvider.Result.create(compute(), PsiModificationTracker.getInstance(cls.project))
            }, false)

        private fun reflectsColumns(cls: PyClass): Boolean {
            val args = cls.findClassAttribute("__table_args__", false, null)?.findAssignedValue() ?: return false
            return REFLECTION_KWARGS.any { "\"$it\"" in args.text || "'$it'" in args.text }
        }

        private fun hasDecorator(m: PyFunction, name: String): Boolean =
            m.decoratorList?.decorators?.any { d -> d.name == name || d.qualifiedName?.firstComponent == name } == true

        private fun isMappedAnnotation(attr: PyTargetExpression): Boolean =
            attr.annotation != null && attr.annotationValue?.startsWith("Mapped[") == true

        fun calleeName(call: PyCallExpression): String? = (call.callee as? PyReferenceExpression)?.referencedName

        fun isColumnCall(e: PyExpression?): Boolean = e is PyCallExpression && calleeName(e) in COLUMN_CALLS

        fun isRelationshipCall(e: PyExpression?): Boolean = e is PyCallExpression && calleeName(e) in RELATIONSHIP_CALLS
    }

    /**
     * Every class "User" / "app.models.User" could mean, resolved the way SQLAlchemy's registry does (by the last
     * segment of the name). A dotted name narrows to the exact match when there is one. Checks treat a reference
     * as valid if ANY candidate satisfies it, so duplicate model names never cause false alarms.
     */
    fun findClasses(ref: String): List<PyClass> {
        val candidates = classes[ref.substringAfterLast('.')] ?: return emptyList()
        if ('.' in ref) candidates.filter { it.qualifiedName == ref }.takeIf { it.isNotEmpty() }?.let { return it }
        return candidates
    }

    /** Best single candidate for navigation: prefer one declared in the same file as the reference. */
    fun findClass(ref: String, context: PsiElement? = null): PyClass? {
        val all = findClasses(ref)
        return all.firstOrNull { context != null && it.containingFile == context.containingFile } ?: all.firstOrNull()
    }

    /** Declarations of a table; accepts schema-qualified names ("auth.links"). */
    fun tableDeclarations(name: String): List<SqlaTable> = tables[name] ?: tables[name.substringAfterLast('.')].orEmpty()

    /** Columns of a table across all of its declarations; null if any declaration's columns can't be known. */
    fun tableColumns(name: String): Map<String, PsiElement>? {
        val out = LinkedHashMap<String, PsiElement>()
        for (t in tableDeclarations(name)) {
            val cols = t.columns() ?: return null
            cols.forEach { (k, v) -> out.putIfAbsent(k, v) }
        }
        return out
    }
}
