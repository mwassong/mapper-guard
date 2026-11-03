package sqlatoolkit

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyElementVisitor
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.stubs.PyClassNameIndex

/**
 * Flags SQLAlchemy string references that would only fail at runtime (mapper configuration or query time):
 * relationship("Usre"), back_populates="adreses", secondary="missing_table", ForeignKey("usres.id"),
 * and filter_by(nmae=...).
 */
class SqlaReferenceInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PyElementVisitor() {
        override fun visitPyStringLiteralExpression(node: PyStringLiteralExpression) {
            val site = SqlaPsi.siteOf(node) ?: return
            val model = SqlaModel.get(node.project)
            if (model.classes.isEmpty() && model.tables.isEmpty()) return // not a SQLAlchemy project
            when (site) {
                is SqlaSite.RelationshipTarget -> {
                    if (!classExistsAnywhere(node.stringValue, node))
                        warn(holder, node, "Unknown mapped class '${node.stringValue}'")
                }
                is SqlaSite.BackPopulates -> {
                    val targets = SqlaPsi.relationshipTargets(site.call, model)
                    val attrs = targets.map { SqlaModel.attributesOf(it) ?: return } // unknown base -> stay quiet
                    if (targets.isNotEmpty() && attrs.none { node.stringValue in it })
                        warn(holder, node, "'${targets.first().name}' has no attribute '${node.stringValue}' for back_populates")
                }
                is SqlaSite.Secondary -> {
                    if (model.tablesComplete && model.tableDeclarations(node.stringValue).isEmpty())
                        warn(holder, node, "Unknown table '${node.stringValue}'")
                }
                SqlaSite.ForeignKey -> {
                    val parts = SqlaPsi.fkParts(node) ?: return
                    if (model.tableDeclarations(parts.table).isEmpty()) {
                        if (model.tablesComplete) holder.registerProblem(node, parts.tableRange, "Unknown table '${parts.table}'")
                    } else {
                        val cols = model.tableColumns(parts.table) ?: return // columns unknown -> stay quiet
                        if (parts.column != null && parts.column !in cols)
                            holder.registerProblem(node, parts.columnRange!!, "Table '${parts.table}' has no column '${parts.column}'")
                    }
                }
            }
        }

        override fun visitPyCallExpression(node: PyCallExpression) {
            val callee = node.callee as? PyReferenceExpression ?: return
            if (callee.referencedName != "filter_by") return
            val entity = filterByEntity(callee.qualifier) ?: return
            val model = SqlaModel.get(node.project)
            val candidates = model.findClasses(entity)
            val cls = candidates.firstOrNull() ?: return
            val attrs = candidates.map { SqlaModel.attributesOf(it) ?: return } // unknown base -> stay quiet
            for (kw in node.arguments.filterIsInstance<PyKeywordArgument>()) {
                val name = kw.keyword ?: continue
                if (attrs.none { name in it }) {
                    val anchor = kw.keywordNode?.psi ?: kw
                    holder.registerProblem(
                        anchor,
                        "'${cls.name}' has no attribute '$name'",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    )
                }
            }
        }
    }

    /**
     * The entity a filter_by() applies to: walk the method chain back to query(X)/select(X).
     * Also handles Flask-SQLAlchemy's Model.query.filter_by() and Model.query.<chain>.filter_by().
     * Any join() in between changes the target entity, so give up rather than guess.
     */
    private fun filterByEntity(start: PyExpression?): String? {
        // Flask-SQLAlchemy: Model.query.filter_by(...)
        if (start is PyReferenceExpression && start.referencedName == "query")
            return (start.qualifier as? PyReferenceExpression)?.referencedName
        var e = start
        while (e is PyCallExpression) {
            val callee = e.callee as? PyReferenceExpression ?: return null
            when (callee.referencedName) {
                "join", "outerjoin", "join_from", "select_from", "with_entities", "add_entity" -> return null
                "query", "select" -> {
                    val args = e.arguments.filter { it !is PyKeywordArgument }
                    if (args.size != 1) return null
                    return (args[0] as? PyReferenceExpression)?.referencedName
                }
            }
            val q = callee.qualifier
            if (q is PyReferenceExpression && q.referencedName == "query")
                return (q.qualifier as? PyReferenceExpression)?.referencedName
            e = q
        }
        return null
    }

    /**
     * SQLAlchemy resolves relationship("X") through its registry at runtime, and classes can be mapped in ways a
     * static scan can't see (imperative mapping, __table__, models built inside functions). So "unknown" means no
     * class of that name exists anywhere: project, libraries, or nested in the reference's own file.
     */
    private fun classExistsAnywhere(ref: String, context: PsiElement): Boolean {
        val simple = ref.substringAfterLast('.')
        if (SqlaModel.get(context.project).findClasses(ref).isNotEmpty()) return true
        if (PyClassNameIndex.find(simple, context.project, true).isNotEmpty()) return true
        return PsiTreeUtil.findChildrenOfType(context.containingFile, PyClass::class.java).any { it.name == simple }
    }

    private fun warn(holder: ProblemsHolder, node: PyStringLiteralExpression, msg: String) =
        holder.registerProblem(node, node.stringValueTextRange, msg)
}
