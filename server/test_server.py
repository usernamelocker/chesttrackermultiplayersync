"""Smoke tests: merge LWW, mass-delete guard, snapshot/restore. No server needed.

Run:  python test_server.py
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(__file__))
import db

def _tmpdb():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    os.unlink(path)
    return path

def test_lww():
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/test"
    db.apply_changes(con, s, [{"key": "minecraft:overworld", "pos": "1,2,3", "deleted": False,
                               "updatedAt": "2026-09-15T10:00:00Z", "updatedBy": "a",
                               "mcVersion": "1.21.11", "items": [{"id": "minecraft:iron_ingot", "count": 5}]}])
    # stale write ignored
    r = db.apply_changes(con, s, [{"key": "minecraft:overworld", "pos": "1,2,3", "deleted": False,
                                   "updatedAt": "2026-09-15T09:00:00Z", "updatedBy": "b",
                                   "mcVersion": "26.2", "items": [{"id": "minecraft:iron_ingot", "count": 99}]}])
    assert r["skipped_stale"] == 1, r
    state = db.full_state(con, s)
    assert state["minecraft:overworld"]["1,2,3"]["items"][0]["count"] == 5
    # newer wins across versions
    db.apply_changes(con, s, [{"key": "minecraft:overworld", "pos": "1,2,3", "deleted": False,
                               "updatedAt": "2026-09-15T11:00:00Z", "updatedBy": "b",
                               "mcVersion": "26.2", "items": [{"id": "minecraft:diamond", "count": 2}]}])
    assert db.full_state(con, s)["minecraft:overworld"]["1,2,3"]["items"][0]["id"] == "minecraft:diamond"
    con.close()
    print("lww ok")

def test_mass_delete_guard():
    assert db.should_quarantine_mass_delete(100, 25) is True
    assert db.should_quarantine_mass_delete(100, 5) is False
    assert db.should_quarantine_mass_delete(0, 0) is False
    assert db.should_quarantine_mass_delete(10, 50) is True
    assert db.should_quarantine_mass_delete(3, 3) is False  # tiny banks exempt from fraction rule
    assert db.should_quarantine_mass_delete(9, 9) is False
    assert db.should_quarantine_mass_delete(10, 3) is True  # 30% of a 10-bank quarantines
    print("guard ok")

def test_snapshot_restore():
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/test"
    db.apply_changes(con, s, [{"key": "k", "pos": "0,0,0", "deleted": False, "updatedAt": "2026-09-15T10:00:00Z",
                               "updatedBy": "a", "mcVersion": "1.21.11", "items": [{"id": "minecraft:stone", "count": 1}]}])
    sid = db.take_snapshot(con, s, keep=5)
    db.apply_changes(con, s, [{"key": "k", "pos": "0,0,0", "deleted": True, "updatedAt": "2026-09-15T12:00:00Z",
                               "updatedBy": "a", "mcVersion": "1.21.11", "items": []}])
    assert db.container_count(con, s) == 0
    n = db.restore_snapshot(con, s, sid)
    assert n == 1 and db.container_count(con, s) == 1
    con.close()
    print("snapshot ok")

if __name__ == "__main__":
    test_lww()
    test_mass_delete_guard()
    test_snapshot_restore()
    print("ALL SERVER TESTS PASSED")
