package sqlatoolkit

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.python.psi.*

/**
 * Flags SQLAlchemy string references that would only fail at runtime (mapper configuration or query time):
 * relationship("Usre"), back_populates="adreses", secondary="missing_table", ForeignKey("usres.id"),
 * and filter_by(nmae=...).
 */
class SqlaReferenceInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PyElementVisitor() {
            override fun visitPyStringLiteralExpression(node: PyStringLiteralExpression) {
                val site = SqlaPsi.siteOf(node) ?: return
                val model = SqlaModel.get(node.project)
                if (model.classes.isEmpty() && model.tables.isEmpty()) return   // not a SQLAlchemy project
                when (site) {
                    is SqlaSite.RelationshipTarget -> {
                        if (model.findClasses(node.stringValue).isEmpty())
                            warn(holder, node, "Unknown mapped class '${node.stringValue}'")
                    }
                    is SqlaSite.BackPopulates -> {
                        val targets = SqlaPsi.relationshipTargets(site.call, model)
                        if (targets.isNotEmpty() && targets.none { node.stringValue in SqlaModel.attributesOf(it) })
                            warn(holder, node, "'${targets.first().name}' has no attribute '${node.stringValue}' for back_populates")
                    }
                    is SqlaSite.Secondary -> {
                        if (node.stringValue !in model.tables)
                            warn(holder, node, "Unknown table '${node.stringValue}'")
                    }
                    SqlaSite.ForeignKey -> {
                        val parts = SqlaPsi.fkParts(node) ?: return
                        if (parts.table !in model.tables) {
                            holder.registerProblem(node, parts.tableRange, "Unknown table '${parts.table}'")
                        } else if (parts.column != null && parts.column !in model.columnsOf(parts.table)) {
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
                for (kw in node.arguments.filterIsInstance<PyKeywordArgument>()) {
                    val name = kw.keyword ?: continue
                    if (candidates.none { name in SqlaModel.attributesOf(it) }) {
                        val anchor = kw.keywordNode?.psi ?: kw
                        holder.registerProblem(anchor, "'${cls.name}' has no attribute '$name'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
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

    private fun warn(holder: ProblemsHolder, node: PyStringLiteralExpression, msg: String) =
        holder.registerProblem(node, node.stringValueTextRange, msg)
}
