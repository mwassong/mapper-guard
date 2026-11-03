package sqlatoolkit

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.*

/** A mapped table: the element that declares it, plus its column name -> declaring element. */
class SqlaTable(val name: String, val declaration: PsiElement, val columns: Map<String, PsiElement>)

/**
 * Project-wide view of SQLAlchemy declarations, rebuilt whenever PSI changes.
 *
 * Only project sources are scanned: string references in user code point at user models, and scanning
 * site-packages would both be slow and offer library-internal classes as completions.
 */
class SqlaModel private constructor(
    /** Class name -> classes with that name that look like mapped models. */
    val classes: Map<String, List<PyClass>>,
    /** __tablename__ / Table("name") -> every declaration of that table (projects can define one twice). */
    val tables: Map<String, List<SqlaTable>>,
) {
    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SqlaModel::class.java)

        fun get(project: Project): SqlaModel =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(build(project), PsiModificationTracker.getInstance(project))
            }

        private fun build(project: Project): SqlaModel {
            val classes = HashMap<String, MutableList<PyClass>>()
            val tables = HashMap<String, MutableList<SqlaTable>>()
            val psi = PsiManager.getInstance(project)
            for (vf in FileTypeIndex.getFiles(PythonFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
                val file = psi.findFile(vf) as? PyFile ?: continue
                for (cls in file.topLevelClasses) {
                    val tableName = tableNameOf(cls)
                    if (tableName == null && !looksMapped(cls)) continue
                    cls.name?.let { classes.getOrPut(it) { mutableListOf() }.add(cls) }
                    if (tableName != null) tables.getOrPut(tableName) { mutableListOf() }.add(SqlaTable(tableName, cls, columnsOf(cls)))
                }
                for (stmt in file.statements) {
                    val table = coreTable(stmt) ?: continue
                    tables.getOrPut(table.name) { mutableListOf() }.add(table)
                }
            }
            LOG.debug("SqlaModel built: ${classes.size} class names, ${tables.size} tables")
            return SqlaModel(classes, tables)
        }

        fun tableNameOf(cls: PyClass): String? {
            val attr = cls.findClassAttribute("__tablename__", true, null) ?: return null
            return (attr.findAssignedValue() as? PyStringLiteralExpression)?.stringValue
        }

        /** A class with no __tablename__ can still be mapped (single-table inheritance, abstract bases). */
        private fun looksMapped(cls: PyClass): Boolean =
            cls.classAttributes.any { isColumnCall(it.findAssignedValue()) || isRelationshipCall(it.findAssignedValue()) } ||
                cls.getAncestorClasses(null).any { it.findClassAttribute("__tablename__", false, null) != null }

        /** Attribute name -> element, for columns and relationships, including those inherited from mixins/bases. */
        fun columnsOf(cls: PyClass): Map<String, PsiElement> {
            val out = LinkedHashMap<String, PsiElement>()
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                for (attr in c.classAttributes) {
                    val name = attr.name ?: continue
                    val value = attr.findAssignedValue()
                    if (isColumnCall(value)) {
                        out.putIfAbsent(name, attr)
                        // mapped_column("db_name", ...) / Column("db_name", ...) sets the DB column name
                        val dbName = ((value as PyCallExpression).arguments.firstOrNull() as? PyStringLiteralExpression)?.stringValue
                        if (dbName != null) out.putIfAbsent(dbName, attr)
                    } else if (attr.annotation != null && attr.annotationValue?.startsWith("Mapped[") == true) {
                        out.putIfAbsent(name, attr)   // `x: Mapped[int]` with no assignment is still a column
                    }
                }
            }
            return out
        }

        /** Attributes usable in filter_by()/back_populates: columns plus relationships plus anything declared. */
        fun attributesOf(cls: PyClass): Set<String> {
            val out = HashSet<String>()
            for (c in listOf(cls) + cls.getAncestorClasses(null)) {
                c.classAttributes.mapNotNullTo(out) { it.name }
                c.methods.filter { m -> m.decoratorList?.decorators?.any { d -> d.name in HYBRID } == true }
                    .mapNotNullTo(out) { it.name }
            }
            return out
        }

        private val HYBRID = setOf("hybrid_property", "hybrid_method", "declared_attr", "column_property", "property")

        private fun coreTable(stmt: PyStatement): SqlaTable? {
            val assign = stmt as? PyAssignmentStatement ?: return null
            val call = assign.assignedValue as? PyCallExpression ?: return null
            if (calleeName(call) != "Table") return null
            val name = (call.arguments.firstOrNull() as? PyStringLiteralExpression)?.stringValue ?: return null
            val cols = LinkedHashMap<String, PsiElement>()
            for (arg in call.arguments) {
                val c = arg as? PyCallExpression ?: continue
                if (calleeName(c) != "Column") continue
                (c.arguments.firstOrNull() as? PyStringLiteralExpression)?.let { cols[it.stringValue] = it }
            }
            return SqlaTable(name, assign, cols)
        }

        fun calleeName(call: PyCallExpression): String? = (call.callee as? PyReferenceExpression)?.referencedName

        fun isColumnCall(e: PyExpression?): Boolean =
            e is PyCallExpression && calleeName(e) in setOf("mapped_column", "Column", "column_property", "deferred")

        fun isRelationshipCall(e: PyExpression?): Boolean =
            e is PyCallExpression && calleeName(e) in setOf("relationship", "relation", "backref")
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

    /** Columns of a table across all of its declarations. */
    fun columnsOf(table: String): Map<String, PsiElement> =
        tables[table].orEmpty().fold(LinkedHashMap()) { acc, t -> t.columns.forEach { (k, v) -> acc.putIfAbsent(k, v) }; acc }
}
