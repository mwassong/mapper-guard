"""Open this file in PyCharm with Mapper Guard installed to take Marketplace screenshots.

It contains deliberate mistakes so the warnings, completion and navigation are all visible.
"""
from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(50))
    addresses: Mapped[list["Address"]] = relationship(back_populates="user")


class Address(Base):
    __tablename__ = "addresses"
    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(100))
    user_id: Mapped[int] = mapped_column(ForeignKey("user.id"))        # mistake: the table is "users"
    user: Mapped["User"] = relationship(back_populates="adresses")     # mistake: typo in "addresses"


def find(session: Session, name: str) -> list[User]:
    return session.query(User).filter_by(nmae=name).all()              # mistake: "name"
