package sqlatoolkit

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.stubs.PyClassNameIndex

/** Ctrl+click and completion inside SQLAlchemy string references. Soft: the inspection owns the warnings. */
class SqlaReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PyStringLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, ctx: ProcessingContext): Array<PsiReference> {
                    val lit = element as PyStringLiteralExpression
                    val site = SqlaPsi.siteOf(lit) ?: return PsiReference.EMPTY_ARRAY
                    val range = lit.stringValueTextRange
                    return when (site) {
                        is SqlaSite.RelationshipTarget -> arrayOf(ClassRef(lit, range))
                        is SqlaSite.BackPopulates -> arrayOf(AttrRef(lit, range, site))
                        is SqlaSite.Secondary -> arrayOf(TableRef(lit, range))
                        SqlaSite.ForeignKey -> {
                            val p = SqlaPsi.fkParts(lit) ?: return arrayOf(TableRef(lit, range))
                            arrayOf(TableRef(lit, p.tableRange), ColumnRef(lit, p.columnRange!!, p.table))
                        }
                    }
                }
            },
        )
    }
}

private abstract class SqlaRef(lit: PyStringLiteralExpression, range: TextRange) :
    PsiReferenceBase<PyStringLiteralExpression>(lit, range, true) {
    protected val model get() = SqlaModel.get(element.project)
}

private class ClassRef(lit: PyStringLiteralExpression, range: TextRange) : SqlaRef(lit, range) {
    // Same notion of "a class with this name" as the inspection: mapped models first, then any project class
    // (imperative mapping, __table__ classes), then a class nested in this file.
    override fun resolve(): PsiElement? {
        model.findClass(value, element)?.let { return it }
        val simple = value.substringAfterLast('.')
        if (!DumbService.isDumb(element.project)) {
            PyClassNameIndex.find(simple, element.project, false).firstOrNull()?.let { return it }
        }
        return PsiTreeUtil.findChildrenOfType(element.containingFile, PyClass::class.java).firstOrNull { it.name == simple }
    }
    override fun getVariants(): Array<Any> =
        model.classes.keys.sorted().map { LookupElementBuilder.create(it).withTypeText("mapped class") }.toTypedArray()
}

private class TableRef(lit: PyStringLiteralExpression, range: TextRange) : SqlaRef(lit, range) {
    override fun resolve(): PsiElement? {
        val all = model.tableDeclarations(value)
        return (all.firstOrNull { it.declaration.containingFile == element.containingFile } ?: all.firstOrNull())?.declaration
    }
    override fun getVariants(): Array<Any> =
        model.tables.keys.sorted().map { LookupElementBuilder.create(it).withTypeText("table") }.toTypedArray()
}

private class ColumnRef(lit: PyStringLiteralExpression, range: TextRange, val table: String) : SqlaRef(lit, range) {
    override fun resolve(): PsiElement? = model.tableColumns(table)?.get(value)
    override fun getVariants(): Array<Any> = model.tableColumns(table).orEmpty().keys.sorted()
        .map { LookupElementBuilder.create(it).withTypeText("column of $table") }.toTypedArray()
}

private class AttrRef(lit: PyStringLiteralExpression, range: TextRange, val site: SqlaSite.BackPopulates) : SqlaRef(lit, range) {
    private fun target() = SqlaPsi.relationshipTarget(site.call, model)
    override fun resolve(): PsiElement? = target()?.findClassAttribute(value, true, null)
    override fun getVariants(): Array<Any> {
        val t = target() ?: return emptyArray()
        return t.classAttributes.filter { SqlaModel.isRelationshipCall(it.findAssignedValue()) }
            .mapNotNull { it.name }.map { LookupElementBuilder.create(it).withTypeText("relationship on ${t.name}") }
            .toTypedArray()
    }
}
