package sqlatoolkit

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Every case here is a line the plugin must get right. The `<warning>` markers are the assertion:
 * the test fails both when a real problem is missed AND when a valid reference is flagged, which is
 * the failure mode that would make users uninstall.
 */
class SqlaReferenceInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(SqlaReferenceInspection())
    }

    private fun models() = myFixture.addFileToProject(
        "models.py",
        """
        from sqlalchemy import ForeignKey, String, Table, Column
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

        class Base(DeclarativeBase):
            pass

        class TimestampMixin:
            created_at: Mapped[str] = mapped_column()

        user_roles = Table("user_roles", Base.metadata, Column("user_id", ForeignKey("users.id")))

        class User(TimestampMixin, Base):
            __tablename__ = "users"
            id: Mapped[int] = mapped_column(primary_key=True)
            name: Mapped[str] = mapped_column(String(50))
            email_addr: Mapped[str] = mapped_column("email", String(100))
            addresses: Mapped[list["Address"]] = relationship(back_populates="user")

        class Address(Base):
            __tablename__ = "addresses"
            id: Mapped[int] = mapped_column(primary_key=True)
            user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
            user: Mapped["User"] = relationship(back_populates="addresses")
        """.trimIndent(),
    )

    private fun check(code: String) {
        models()
        myFixture.configureByText(
            "check.py",
            """
            from sqlalchemy import ForeignKey
            from sqlalchemy.orm import Mapped, mapped_column, relationship, Session
            from models import Base, User, Address

            """.trimIndent() + "\n" + code.trimIndent(),
        )
        myFixture.checkHighlighting(true, false, false)
    }

    // ---- must be flagged -------------------------------------------------------------------
    fun testUnknownMappedClass() = check(
        """
        class A(Base):
            __tablename__ = "a1"
            id: Mapped[int] = mapped_column(primary_key=True)
            u: Mapped["User"] = relationship("<warning descr="Unknown mapped class 'Usre'">Usre</warning>")
        """,
    )

    fun testUnknownForeignKeyTable() = check(
        """
        class A(Base):
            __tablename__ = "a2"
            id: Mapped[int] = mapped_column(primary_key=True)
            x: Mapped[int] = mapped_column(ForeignKey("<warning descr="Unknown table 'usres'">usres</warning>.id"))
        """,
    )

    fun testUnknownForeignKeyColumn() = check(
        """
        class A(Base):
            __tablename__ = "a3"
            id: Mapped[int] = mapped_column(primary_key=True)
            x: Mapped[int] = mapped_column(ForeignKey("users.<warning descr="Table 'users' has no column 'idd'">idd</warning>"))
        """,
    )

    fun testUnknownBackPopulates() = check(
        """
        class A(Base):
            __tablename__ = "a4"
            id: Mapped[int] = mapped_column(primary_key=True)
            u: Mapped["User"] = relationship("User", back_populates="<warning descr="'User' has no attribute 'adreses' for back_populates">adreses</warning>")
        """,
    )

    fun testUnknownSecondaryTable() = check(
        """
        class A(Base):
            __tablename__ = "a5"
            id: Mapped[int] = mapped_column(primary_key=True)
            u: Mapped[list["User"]] = relationship(secondary="<warning descr="Unknown table 'user_rolez'">user_rolez</warning>")
        """,
    )

    fun testUnknownFilterByColumn() = check(
        """
        def q(s: Session) -> None:
            s.query(User).filter_by(<warning descr="'User' has no attribute 'nmae'">nmae</warning>="x")
        """,
    )

    fun testUnknownFilterByColumnFlaskStyle() = check(
        """
        def q() -> None:
            User.query.filter_by(<warning descr="'User' has no attribute 'nmae'">nmae</warning>="x")
        """,
    )

    // ---- must NOT be flagged (false positives are the thing that kills adoption) ------------
    fun testValidReferences() = check(
        """
        class A(Base):
            __tablename__ = "a6"
            id: Mapped[int] = mapped_column(primary_key=True)
            fk: Mapped[int] = mapped_column(ForeignKey("users.id"))
            db_col: Mapped[int] = mapped_column(ForeignKey("users.email"))   # DB column name, not attribute name
            u: Mapped["User"] = relationship("User", back_populates="addresses")
            dotted: Mapped["User"] = relationship("models.User", viewonly=True)
            many: Mapped[list["User"]] = relationship(secondary="user_roles", viewonly=True)
        """,
    )

    fun testValidFilterBy() = check(
        """
        def q(s: Session) -> None:
            s.query(User).filter_by(name="x", email_addr="y", created_at=None)
            User.query.filter_by(id=1)
            s.query(User).join(Address).filter_by(street="unknown entity after join - not checked")
        """,
    )

    // ---- false-alarm regressions from the 2026-11-03 code review --------------------------------
    // Each one is valid SQLAlchemy that 0.1.0 flagged. Isolated files, so project-wide state can't leak.
    private fun quiet(vararg files: Pair<String, String>) {
        for ((name, text) in files.dropLast(1)) myFixture.addFileToProject(name, text.trimIndent())
        val (name, text) = files.last()
        myFixture.configureByText(name, text.trimIndent())
        myFixture.checkHighlighting(true, false, false)
    }

    fun testDeclaredAttrTablenameIsNotUnknownTable() = quiet(
        "dyn.py" to """
        from sqlalchemy import ForeignKey
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, declared_attr

        class DynBase(DeclarativeBase):
            @declared_attr.directive
            def __tablename__(cls):
                return cls.__name__.lower()

        class Thing(DynBase):
            id: Mapped[int] = mapped_column(primary_key=True)

        class Other(DynBase):
            id: Mapped[int] = mapped_column(primary_key=True)
            thing_id: Mapped[int] = mapped_column(ForeignKey("thing.id"))
        """,
    )

    fun testReflectedTableColumnsAreNotFlagged() = quiet(
        "reflected.py" to """
        from sqlalchemy import ForeignKey, MetaData, Table
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

        class Base(DeclarativeBase):
            pass

        legacy = Table("legacy", Base.metadata, autoload_with=None)

        class Uses(Base):
            __tablename__ = "uses"
            id: Mapped[int] = mapped_column(primary_key=True)
            legacy_id: Mapped[int] = mapped_column(ForeignKey("legacy.id"))
        """,
    )

    fun testClassMappedWithoutColumnsIsNotUnknownClass() = quiet(
        "imperative.py" to """
        from sqlalchemy import Column, Integer, Table
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

        class Base(DeclarativeBase):
            pass

        class Plain:
            pass

        class Real(Base):   # a top-level model, so the project counts as a SQLAlchemy project
            __tablename__ = "real"
            id: Mapped[int] = mapped_column(primary_key=True)
            plain = relationship("Plain")

        def make_models():
            class Local(Base):
                __tablename__ = "local"
                id: Mapped[int] = mapped_column(primary_key=True)
                plain = relationship("Plain")
                me = relationship("Local")
            return Local
        """,
    )

    fun testSchemaQualifiedSecondaryIsKnown() = quiet(
        "schema.py" to """
        from sqlalchemy import Column, ForeignKey, Table
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

        class Base(DeclarativeBase):
            pass

        links = Table("links", Base.metadata, Column("a_id", ForeignKey("a.id")), schema="auth")

        class A(Base):
            __tablename__ = "a"
            id: Mapped[int] = mapped_column(primary_key=True)
            peers: Mapped[list["A"]] = relationship(secondary="auth.links", viewonly=True)
        """,
    )

    fun testUnresolvedBaseClassMakesAttributesUnknown() = quiet(
        "libbase.py" to """
        from sqlalchemy import ForeignKey
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, Session
        from some_auth_library import SQLAlchemyBaseUserTable

        class Base(DeclarativeBase):
            pass

        class Member(SQLAlchemyBaseUserTable, Base):
            __tablename__ = "member"
            nickname: Mapped[str] = mapped_column()

        class Post(Base):
            __tablename__ = "post"
            id: Mapped[int] = mapped_column(primary_key=True)
            author_email: Mapped[str] = mapped_column(ForeignKey("member.email"))

        def q(s: Session) -> None:
            s.query(Member).filter_by(email="x", nickname="y")
        """,
    )

    fun testDeclaredAttrColumnIsAColumn() = quiet(
        "mixin.py" to """
        from sqlalchemy import ForeignKey
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, declared_attr

        class Base(DeclarativeBase):
            pass

        class TenantMixin:
            @declared_attr
            def tenant_id(cls):
                return mapped_column(ForeignKey("tenant.id"))

        class Tenant(Base):
            __tablename__ = "tenant"
            id: Mapped[int] = mapped_column(primary_key=True)

        class Invoice(TenantMixin, Base):
            __tablename__ = "invoice"
            id: Mapped[int] = mapped_column(primary_key=True)

        class Line(Base):
            __tablename__ = "line"
            id: Mapped[int] = mapped_column(primary_key=True)
            tenant_id: Mapped[int] = mapped_column(ForeignKey("invoice.tenant_id"))
        """,
    )

    fun testSchemaQualifiedUnknownSecondaryStillFlagged() = check(
        """
        class A(Base):
            __tablename__ = "a7"
            id: Mapped[int] = mapped_column(primary_key=True)
            u: Mapped[list["User"]] = relationship(secondary="<warning descr="Unknown table 'auth.nope'">auth.nope</warning>")
        """,
    )

    // ---- second review pass (2026-11-03) ----------------------------------------------------------
    fun testColumnsFromHelpersAreNotFlagged() = quiet(
        "helpers.py" to """
        from sqlalchemy import Column, ForeignKey, Integer, Table
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

        def timestamps():
            return [Column("created_at", Integer), Column("updated_at", Integer)]

        def uuid_pk():
            return Column(Integer, primary_key=True)

        class Base(DeclarativeBase):
            pass

        audit = Table("audit", Base.metadata, Column("id", Integer, primary_key=True), *timestamps())

        class Doc(Base):
            __tablename__ = "doc"
            id = uuid_pk()

        class Ref(Base):
            __tablename__ = "ref"
            pk: Mapped[int] = mapped_column(primary_key=True)
            a: Mapped[int] = mapped_column(ForeignKey("audit.created_at"))
            b: Mapped[int] = mapped_column(ForeignKey("doc.id"))
        """,
    )

    fun testOtherLibrariesTableDoesNotDisableUnknownTable() = check(
        """
        from rich.table import Table   # not SQLAlchemy; unresolvable here, as with no interpreter configured
        header = "Name"
        grid = Table(header, "Age")

        class A(Base):
            __tablename__ = "a8"
            id: Mapped[int] = mapped_column(primary_key=True)
            x: Mapped[int] = mapped_column(ForeignKey("<warning descr="Unknown table 'usres'">usres</warning>.id"))
        """,
    )

    fun testNoCrashWhileIndexing() {
        models()
        myFixture.configureByText("dumb.py", "from sqlalchemy.orm import relationship\nx = relationship(\"Us<caret>er\")\n")
        val ref = myFixture.getReferenceAtCaretPositionWithAssertion()
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
            com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches() // force a rebuild while indexes are unavailable
            ref.resolve() // must not throw IndexNotReadyException; null is fine while indexing
            ref.variants
        }
    }

    // ---- third review pass (2026-11-03) -----------------------------------------------------------
    fun testDynamicAndConcatenatedStringsAreSkipped() = check(
        """
        prefix = "Us"
        class A(Base):
            __tablename__ = "a9"
            id: Mapped[int] = mapped_column(primary_key=True)
            dyn: Mapped["User"] = relationship(f"{prefix}er")
            cat: Mapped[int] = mapped_column(ForeignKey("us" "res.idd"))
            esc: Mapped[int] = mapped_column(ForeignKey("users.\\x69dd"))
        """,
    )

    fun testUnresolvedMixinNamedModelMakesColumnsUnknown() = quiet(
        "vendor.py" to """
        from sqlalchemy import ForeignKey
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
        from vendor_lib import Model

        class Base(DeclarativeBase):
            pass

        class Order(Model, Base):
            __tablename__ = "order"
            note: Mapped[str] = mapped_column()

        class Ship(Base):
            __tablename__ = "ship"
            id: Mapped[int] = mapped_column(primary_key=True)
            order_id: Mapped[int] = mapped_column(ForeignKey("order.id"))
        """,
    )

    fun testCtrlClickWorksForImperativelyMappedClass() {
        models()
        myFixture.addFileToProject("plain.py", "class Plain:\n    pass\n")
        myFixture.configureByText("nav2.py", "from sqlalchemy.orm import relationship\nx = relationship(\"Pla<caret>in\")\n")
        assertEquals("Plain", resolvedName())
    }

    fun testEachProblemReportedExactlyOnce() {
        models()
        myFixture.configureByText(
            "once.py",
            "from models import User\n\ndef get(uid):\n    return User.query.filter_by(idd=uid).first_or_404()\n",
        )
        val hits = myFixture.doHighlighting().filter { it.description == "'User' has no attribute 'idd'" }
        assertEquals("duplicates: ${hits.map { it.startOffset }}", 1, hits.size)
    }

    // ---- final pass: corpus sweep (Indico, Superset) and mutation-testing gaps -------------------
    fun testBackrefCreatedAttributesAreKnown() = quiet(
        "survey.py" to """
        from sqlalchemy import ForeignKey
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, backref, Session

        class Base(DeclarativeBase):
            pass

        class Survey(Base):
            __tablename__ = "surveys"
            id: Mapped[int] = mapped_column(primary_key=True)
            items = relationship("SurveyItem", backref=backref("survey", lazy=True))
            notes = relationship("Note", backref="owner")

        class SurveyItem(Base):
            __tablename__ = "items"
            id: Mapped[int] = mapped_column(primary_key=True)
            survey_id: Mapped[int] = mapped_column(ForeignKey("surveys.id"))

        class SurveySection(SurveyItem):   # inherits the backref-created attribute
            __mapper_args__ = {"polymorphic_identity": "section"}

        class Note(Base):
            __tablename__ = "notes"
            id: Mapped[int] = mapped_column(primary_key=True)
            survey_id: Mapped[int] = mapped_column(ForeignKey("surveys.id"))

        def q(s: Session, sv: Survey) -> None:
            s.query(SurveySection).filter_by(survey=sv)
            s.query(Note).filter_by(owner=sv)
        """,
    )

    fun testBackrefDoesNotHideRealTypos() = check(
        """
        def q(s: Session) -> None:
            s.query(User).filter_by(<warning descr="'User' has no attribute 'surveyy'">surveyy</warning>=1)
        """,
    )

    fun testClassImportedFromUninstalledLibraryIsNotUnknown() = quiet(
        "views.py" to "from flask_appbuilder.security.sqla.models import Group\n",
        "subjects.py" to """
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

        class Base(DeclarativeBase):
            pass

        class Subject(Base):
            __tablename__ = "subjects"
            id: Mapped[int] = mapped_column(primary_key=True)
            group = relationship("Group")
        """,
    )

    fun testUnmappedClassInAnotherFileIsNotUnknown() = quiet(
        "plain2.py" to "class Plain2:\n    pass\n",
        "user_of_plain.py" to """
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

        class Base(DeclarativeBase):
            pass

        class Holder(Base):
            __tablename__ = "holders"
            id: Mapped[int] = mapped_column(primary_key=True)
            p = relationship("Plain2")
        """,
    )

    fun testSchemaQualifiedForeignKeyColumn() = check(
        """
        class A(Base):
            __tablename__ = "a10"
            id: Mapped[int] = mapped_column(primary_key=True)
            x: Mapped[int] = mapped_column(ForeignKey("auth.users.<warning descr="Table 'users' has no column 'idd'">idd</warning>"))
        """,
    )

    fun testTypingListAnnotationTarget() = check(
        """
        from typing import List
        class A(Base):
            __tablename__ = "a11"
            id: Mapped[int] = mapped_column(primary_key=True)
            us: Mapped[List["User"]] = relationship(back_populates="<warning descr="'User' has no attribute 'nope' for back_populates">nope</warning>")
        """,
    )

    fun testReflectedTableWithSomeColumnsIsStillUnknown() = quiet(
        "reflected2.py" to """
        from sqlalchemy import Column, ForeignKey, Integer, Table
        from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

        class Base(DeclarativeBase):
            pass

        acct = Table("acct", Base.metadata, Column("id", Integer, primary_key=True), autoload_with=None)

        class Uses2(Base):
            __tablename__ = "uses2"
            id: Mapped[int] = mapped_column(primary_key=True)
            owner: Mapped[int] = mapped_column(ForeignKey("acct.owner_id"))
        """,
    )

    // ---- issue #1: hybrid declarative mapping, `__table__ = Table(...)` ------------------------------
    // SQLAlchemy maps every column of __table__ onto the class, so those columns are attributes.
    fun testHybridTableColumnsAreAttributes() = quiet(
        "hybrid.py" to """
        from sqlalchemy import Column, Integer, String, Table
        from sqlalchemy.orm import DeclarativeBase, Session

        class Base(DeclarativeBase):
            pass

        class Account(Base):
            __table__ = Table(
                "accounts", Base.metadata,
                Column("id", Integer, primary_key=True),
                Column("name", String(50)),
            )

        def find(session: Session, name: str):
            return session.query(Account).filter_by(name=name).all()
        """,
    )

    // __table__ bound to a variable, or built from columns we can't see: attributes unknown, stay quiet.
    fun testHybridTableWithUnknownColumnsIsQuiet() = quiet(
        "hybrid2.py" to """
        from sqlalchemy import Column, Integer, String, Table
        from sqlalchemy.orm import DeclarativeBase, Session

        class Base(DeclarativeBase):
            pass

        accounts = Table("accounts2", Base.metadata, Column("id", Integer, primary_key=True))

        def cols():
            return [Column("name", String(50))]

        class Account2(Base):
            __table__ = accounts

        class Account3(Base):
            __table__ = Table("accounts3", Base.metadata, Column("id", Integer, primary_key=True), *cols())

        class Account4(Base):
            __table__ = Table("accounts4", Base.metadata, autoload_with=None)

        def find(session: Session):
            session.query(Account2).filter_by(name="x")
            session.query(Account3).filter_by(name="x")
            session.query(Account4).filter_by(name="x")
        """,
    )

    // The columns are known exactly, so a typo on a hybrid-mapped class is still caught.
    fun testHybridTableTypoStillFlagged() = quiet(
        "hybrid3.py" to """
        from sqlalchemy import Column, Integer, String, Table
        from sqlalchemy.orm import DeclarativeBase, Session

        class Base(DeclarativeBase):
            pass

        class Account5(Base):
            __table__ = Table(
                "accounts5", Base.metadata,
                Column("id", Integer, primary_key=True),
                Column("name", String(50)),
            )

        def find(session: Session):
            session.query(Account5).filter_by(name="x", <warning descr="'Account5' has no attribute 'nmae'">nmae</warning>="x")
        """,
    )

    fun testFlaskSqlalchemyAutomaticTableNames() {
        myFixture.configureByText(
            "flaskauto.py",
            """
            from flask_sqlalchemy import SQLAlchemy

            db = SQLAlchemy()

            class UserRole(db.Model):          # Flask-SQLAlchemy names this table "user_role"
                id = db.Column(db.Integer, primary_key=True)

            class HTTPServerLog(db.Model):     # -> "http_server_log"
                id = db.Column(db.Integer, primary_key=True)

            class Grant(db.Model):
                id = db.Column(db.Integer, primary_key=True)
                role_id = db.Column(db.Integer, db.ForeignKey("user_role.id"))
                log_id = db.Column(db.Integer, db.ForeignKey("http_server_log.id"))
                bad_id = db.Column(db.Integer, db.ForeignKey("<warning descr="Unknown table 'user_roll'">user_roll</warning>.id"))
            """.trimIndent(),
        )
        myFixture.checkHighlighting(true, false, false)
    }

    /** Documented in the Marketplace "Getting Started": one warning can be silenced with a comment. */
    fun testNoinspectionCommentSuppresses() {
        models()
        myFixture.configureByText(
            "suppressed.py",
            "from sqlalchemy.orm import relationship\n\n# noinspection SqlaReference\nx = relationship(\"Usre\")\n" +
                "y = relationship(\"Usre\")\n",
        )
        val hits = myFixture.doHighlighting().filter { it.description == "Unknown mapped class 'Usre'" }
        assertEquals("only the un-suppressed line should warn", 1, hits.size)
    }

    // ---- navigation and completion (advertised features, previously untested) -----------------
    private fun atCaret(code: String) {
        models()
        myFixture.configureByText(
            "nav.py",
            "from sqlalchemy import ForeignKey\n" +
                "from sqlalchemy.orm import Mapped, mapped_column, relationship\n" +
                "from models import Base, User\n\n" +
                code.trimIndent(),
        )
    }

    private fun resolvedName(): String? {
        val ref = myFixture.getReferenceAtCaretPositionWithAssertion()
        return (ref.resolve() as? com.intellij.psi.PsiNamedElement)?.name
    }

    fun testCtrlClickClassResolvesToModel() {
        atCaret("x = relationship(\"Us<caret>er\")")
        assertEquals("User", resolvedName())
    }

    fun testCtrlClickForeignKeyColumnResolves() {
        atCaret("y = mapped_column(ForeignKey(\"users.na<caret>me\"))")
        assertEquals("name", resolvedName())
    }

    fun testCtrlClickBackPopulatesResolves() {
        atCaret("z = relationship(\"User\", back_populates=\"addr<caret>esses\")")
        assertEquals("addresses", resolvedName())
    }

    fun testCompletionOffersModelsAndTables() {
        atCaret("x = relationship(\"<caret>\")")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("got $offered", offered.containsAll(listOf("User", "Address")))
    }

    fun testCompletionOffersColumnsOfTable() {
        atCaret("y = mapped_column(ForeignKey(\"users.<caret>\"))")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("got $offered", offered.containsAll(listOf("id", "name", "email")))
    }

    fun testNonSqlalchemyCodeIsUntouched() = check(
        """
        def relationship(x): return x
        def other() -> None:
            relationship("NotAModel")
        """,
    )
}
