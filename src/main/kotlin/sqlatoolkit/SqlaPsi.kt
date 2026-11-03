package sqlatoolkit

import com.intellij.openapi.util.TextRange
import com.jetbrains.python.psi.PyArgumentList
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyLambdaExpression
import com.jetbrains.python.psi.PyQualifiedNameOwner
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression

/** Where a string literal sits inside a SQLAlchemy call, if anywhere interesting. */
sealed class SqlaSite {
    /** relationship("User") — first positional argument. */
    class RelationshipTarget(val call: PyCallExpression) : SqlaSite()

    /** relationship(..., back_populates="addresses"). */
    class BackPopulates(val call: PyCallExpression) : SqlaSite()

    /** relationship(..., secondary="user_roles"). */
    class Secondary(val call: PyCallExpression) : SqlaSite()

    /** ForeignKey("users.id") / ForeignKey("schema.users.id"). */
    object ForeignKey : SqlaSite()
}

object SqlaPsi {
    fun siteOf(lit: PyStringLiteralExpression): SqlaSite? {
        val parent = lit.parent
        val kw = parent as? PyKeywordArgument
        val argList = (kw?.parent ?: parent) as? PyArgumentList ?: return null
        val call = argList.parent as? PyCallExpression ?: return null
        val name = SqlaModel.calleeName(call) ?: return null
        if (!isSqlalchemyCall(call)) return null
        return when {
            name in REL && kw == null && call.arguments.firstOrNull() === lit -> SqlaSite.RelationshipTarget(call)
            name in REL && kw?.keyword == "back_populates" -> SqlaSite.BackPopulates(call)
            name in REL && kw?.keyword == "secondary" -> SqlaSite.Secondary(call)
            name == "ForeignKey" && kw == null && call.arguments.firstOrNull() === lit -> SqlaSite.ForeignKey
            else -> null
        }
    }

    private val REL = setOf("relationship", "relation")

    /**
     * True when the callee resolves into SQLAlchemy (or Flask-SQLAlchemy), so a project's own function
     * named `relationship()` is left alone. If the call does not resolve at all — no interpreter
     * configured, or the library is not installed — we assume it IS SQLAlchemy, because the plugin
     * should still work in a project that has not been set up yet.
     */
    fun isSqlalchemyCall(call: PyCallExpression): Boolean {
        val resolved = (call.callee as? PyReferenceExpression)?.reference?.resolve() ?: return true
        val qualified = (resolved as? PyQualifiedNameOwner)?.qualifiedName
        if (qualified != null) return qualified.startsWith("sqlalchemy") || qualified.startsWith("flask_sqlalchemy")
        val path = resolved.containingFile?.virtualFile?.path ?: return true
        return "sqlalchemy" in path
    }

    /**
     * The class a relationship points at: its first argument (string, class, or lambda returning a class),
     * else the `Mapped[...]` annotation on the attribute it is assigned to.
     */
    fun relationshipTargets(call: PyCallExpression, model: SqlaModel): List<PyClass> {
        val name = relationshipTargetName(call) ?: return emptyList()
        return model.findClasses(name)
    }

    fun relationshipTarget(call: PyCallExpression, model: SqlaModel): PyClass? =
        relationshipTargetName(call)?.let { model.findClass(it, call) }

    private fun relationshipTargetName(call: PyCallExpression): String? {
        when (val first = call.arguments.firstOrNull { it !is PyKeywordArgument }) {
            is PyStringLiteralExpression -> return first.stringValue
            is PyReferenceExpression -> return first.referencedName
            is PyLambdaExpression -> return (first.body as? PyReferenceExpression)?.referencedName
            else -> {}
        }
        val target = call.parent as? PyAssignmentStatement
        val attr = target?.targets?.firstOrNull() as? PyTargetExpression ?: return null
        return attr.annotationValue?.let { annotationClassName(it) }
    }

    /** Mapped["User"], Mapped[List["User"]], Mapped[list[User]], Mapped[Optional["User"]], Mapped["User | None"] -> User */
    fun annotationClassName(ann: String): String? {
        val inner = ann.removePrefix("Mapped[").removeSuffix("]")
        val skip =
            setOf("List", "list", "Set", "set", "Optional", "Sequence", "None", "typing", "Mapped", "WriteOnlyMapped", "DynamicMapped")
        return Regex("[A-Za-z_][A-Za-z0-9_.]*").findAll(inner).map { it.value.substringAfterLast('.') }
            .firstOrNull { it !in skip }
    }

    /** ForeignKey("schema.table.col") -> ("table", range-of-table, "col", range-of-col) within the literal. */
    data class FkParts(val table: String, val tableRange: TextRange, val column: String?, val columnRange: TextRange?)

    fun fkParts(lit: PyStringLiteralExpression): FkParts? {
        val value = lit.stringValue
        val offset = lit.stringValueTextRange.startOffset
        val dot = value.lastIndexOf('.')
        if (dot <= 0) return null
        val tableStart = value.lastIndexOf('.', dot - 1) + 1
        return FkParts(
            value.substring(tableStart, dot),
            TextRange(offset + tableStart, offset + dot),
            value.substring(dot + 1),
            TextRange(offset + dot + 1, offset + value.length),
        )
    }
}
