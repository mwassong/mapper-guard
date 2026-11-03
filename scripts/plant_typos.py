"""Plant (or remove) the six control typos used by real_world_check.sh.

Edits are matched by CONTENT, not line number: upstream `main` moves, and line-based planting
silently no-ops, which turns the control into a lie ("0 warnings" that proves nothing).
Exits non-zero if any edit cannot be applied exactly once.
"""
import pathlib
import sys

EDITS = [
    ("ctfd_py/CTFd/models/__init__.py", 'db.ForeignKey("users.id")', 'db.ForeignKey("usrs.id")'),
    ("ctfd_py/CTFd/models/__init__.py", 'db.relationship("Users"', 'db.relationship("Userz"'),
    ("ctfd_py/CTFd/api/v1/audiences.py", "filter_by(id=audience_id)", "filter_by(idd=audience_id)"),
    ("mlflow_store/store/tracking/dbmodels/models.py",
     'ForeignKey("experiments.experiment_id")', 'ForeignKey("experiments.experiment_idd")'),
    ("mlflow_store/store/tracking/dbmodels/models.py",
     'relationship("SqlExperiment"', 'relationship("SqlExperimen"'),
    ("mlflow_store/store/tracking/sqlalchemy_store.py",
     "filter_by(run_uuid=run_id, key=metric_key)", "filter_by(run_uid=run_id, key=metric_key)"),
]


def main(work: str, undo: bool = False) -> None:
    root = pathlib.Path(work)
    for rel, good, bad in EDITS:
        src, dst = (bad, good) if undo else (good, bad)
        path = root / rel
        text = path.read_text()
        if src not in text:
            sys.exit("planting failed: %r not found in %s (upstream changed?)" % (src, rel))
        path.write_text(text.replace(src, dst, 1))
    print("  %s 6 typos across %d files" % ("removed" if undo else "planted", len({e[0] for e in EDITS})))


if __name__ == "__main__":
    main(sys.argv[1], undo="--undo" in sys.argv)
