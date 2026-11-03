package sqlatoolkit

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
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
) {
    /** Per-file facts. Depends only on the file's own text, so it is safe to cache against the file. */
    private class FileFacts(
        val mappedClasses: List<PyClass>,
        val tables: List<SqlaTable>,
        /** Classes with no table name of their own: their name may be inherited, resolved at aggregate time. */
        val inheritsTableName: List<PyClass>,
        val hasDynamicTableNames: Boolean,
    )

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlaModel::class.java)

        /** Superclass names that are declarative bases: they contribute no user columns, resolvable or not. */
        private val DECLARATIVE_BASES = setOf(
            "object",
            "Base",
            "Model",
            "DeclarativeBase",
            "DeclarativeBaseNoMeta",
            "MappedAsDataclass",
            "AsyncAttrs",
            "SQLModel",
        )
        private val COLUMN_CALLS = setOf("mapped_column", "Column", "column_property", "deferred")
        private val RELATIONSHIP_CALLS = setOf("relationship", "relation", "backref")
        private val ATTRIBUTE_DECORATORS = setOf("hybrid_property", "hybrid_method", "declared_attr", "column_property", "property")
        private val REFLECTION_KWARGS = setOf("autoload_with", "autoload", "extend_existing", "keep_existing")

        fun get(project: Project): SqlaModel = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(aggregate(project), PsiModificationTracker.getInstance(project))
        }

        private fun factsOf(file: PyFile): FileFacts =
            CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(extract(file), file) }

        private fun aggregate(project: Project): SqlaModel {
            val classes = HashMap<String, MutableList<PyClass>>()
            val tables = HashMap<String, MutableList<SqlaTable>>()
            var complete = true
            val psi = PsiManager.getInstance(project)
            for (vf in FileTypeIndex.getFiles(PythonFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
                ProgressManager.checkCanceled()
                val facts = factsOf(psi.findFile(vf) as? PyFile ?: continue)
                for (cls in facts.mappedClasses) cls.name?.let { classes.getOrPut(it) { mutableListOf() }.add(cls) }
                for (t in facts.tables) tables.getOrPut(t.name) { mutableListOf() }.add(t)
                if (facts.hasDynamicTableNames) complete = false
                // A table name inherited across files (e.g. from a library base) can only be resolved here.
                for (cls in facts.inheritsTableName) {
                    when (val inherited = inheritedTableName(cls)) {
                        null -> if (!ancestorsKnown(cls)) complete = false
                        else -> tables.getOrPut(inherited) { mutableListOf() }.add(SqlaTable(inherited, cls, cls, null))
                    }
                }
            }
            LOG.debug("SqlaModel: ${classes.size} class names, ${tables.size} tables, complete=$complete")
            return SqlaModel(classes, tables, complete)
        }

        private fun extract(file: PyFile): FileFacts {
            val mapped = ArrayList<PyClass>()
            val tables = ArrayList<SqlaTable>()
            val inherits = ArrayList<PyClass>()
            var dynamic = false
            for (cls in file.topLevelClasses) {
                when (val own = ownTableName(cls)) {
                    is TableName.Literal -> tables.add(SqlaTable(own.name, cls, cls, null))
                    TableName.Dynamic -> dynamic = true
                    // `__table__ = Table("x", ...)` / `__table__ = some_table` are captured by the Table scan below;
                    // anything else (a computed table) means a table name we can't know.
                    TableName.None -> {
                        val t = cls.findClassAttribute("__table__", false, null)?.findAssignedValue()
                        if (t != null && t !is PyReferenceExpression && !(t is PyCallExpression && calleeName(t) == "Table")) dynamic = true
                    }
                }
                if (looksMapped(cls)) {
                    mapped.add(cls)
                    if (ownTableName(cls) == TableName.None) inherits.add(cls)
                }
            }
            // Core tables can be declared anywhere (module level, inside functions, in __table__ assignments).
            for (call in PsiTreeUtil.findChildrenOfType(file, PyCallExpression::class.java)) {
                if (calleeName(call) != "Table" || !SqlaPsi.isSqlalchemyCall(call)) continue
                val positional = call.arguments.filter { it !is PyKeywordArgument }
                if (positional.size < 2) continue // SQLAlchemy's Table(name, metadata, ...); anything else is another Table
                val name = (positional[0] as? PyStringLiteralExpression)?.stringValue
                if (name == null) {
                    dynamic = true
                    continue
                }
                val reflected = call.arguments.any { it is PyKeywordArgument && it.keyword in REFLECTION_KWARGS }
                val cols = LinkedHashMap<String, PsiElement>()
                for (arg in positional.drop(2)) {
                    val c = arg as? PyCallExpression ?: continue
                    if (calleeName(c) != "Column") continue
                    (c.arguments.firstOrNull() as? PyStringLiteralExpression)?.let { cols[it.stringValue] = it }
                }
                tables.add(SqlaTable(name, call, null, if (reflected || cols.isEmpty()) null else cols))
            }
            return FileFacts(mapped, tables, inherits, dynamic)
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

        private fun inheritedTableName(cls: PyClass): String? =
            (cls.findClassAttribute("__tablename__", true, null)?.findAssignedValue() as? PyStringLiteralExpression)?.stringValue

        /** Mapped judged from the class's own body only, so per-file caching stays correct. */
        private fun looksMapped(cls: PyClass): Boolean = ownTableName(cls) != TableName.None ||
            cls.findClassAttribute("__table__", false, null) != null ||
            cls.findClassAttribute("__mapper_args__", false, null) != null ||
            cls.classAttributes.any { isColumnCall(it.findAssignedValue()) || isRelationshipCall(it.findAssignedValue()) }

        /**
         * False when some base class can't be resolved, so attributes it might contribute are unknown.
         * Declarative bases (`db.Model`, `Base`, ...) are exempt: they add no user columns.
         */
        fun ancestorsKnown(cls: PyClass): Boolean {
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                for (expr in c.superClassExpressions) {
                    val ref = expr as? PyReferenceExpression ?: continue
                    if (ref.referencedName in DECLARATIVE_BASES) continue
                    if (ref.reference.resolve() == null) return false
                }
            }
            return true
        }

        /** Column name (attribute and DB name) -> element; null when the columns can't all be known. */
        fun declaredColumns(cls: PyClass): Map<String, PsiElement>? {
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
        fun attributesOf(cls: PyClass): Set<String>? {
            if (!ancestorsKnown(cls)) return null
            val out = HashSet<String>()
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                c.classAttributes.mapNotNullTo(out) { it.name }
                c.methods.filter { m -> ATTRIBUTE_DECORATORS.any { hasDecorator(m, it) } }.mapNotNullTo(out) { it.name }
            }
            return out
        }

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
