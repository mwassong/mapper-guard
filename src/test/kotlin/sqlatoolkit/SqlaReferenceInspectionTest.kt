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
