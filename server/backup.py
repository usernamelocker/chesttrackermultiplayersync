"""Filesystem .backup + snapshot prune. Run from cron/systemd timer. stdlib only."""
from __future__ import annotations
import shutil
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import config  # noqa: E402
import db  # noqa: E402

def main() -> int:
    src = Path(config.DB_PATH)
    if not src.exists():
        print(f"no db at {src}, nothing to back up")
        return 0
    out_dir = Path(__file__).parent / "backups"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    dst = out_dir / f"cmsync-{stamp}.db"
    # online-safe SQLite backup
    src_con = sqlite3.connect(str(src))
    dst_con = sqlite3.connect(str(dst))
    try:
        src_con.backup(dst_con)
    finally:
        dst_con.close()
        src_con.close()
    # keep last 14 filesystem backups
    all_baks = sorted(out_dir.glob("cmsync-*.db"))
    for old in all_baks[:-14]:
        old.unlink()
    # prune tombstones + ensure snapshot exists
    con = db.connect(config.DB_PATH)
    try:
        db.prune_tombstones(con, config.TOMBSTONE_TTL_DAYS)
    finally:
        con.close()
    print(f"backup -> {dst}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
