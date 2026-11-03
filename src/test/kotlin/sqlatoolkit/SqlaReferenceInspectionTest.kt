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

    fun testNonSqlalchemyCodeIsUntouched() = check(
        """
        def relationship(x): return x
        def other() -> None:
            relationship("NotAModel")
        """,
    )
}
