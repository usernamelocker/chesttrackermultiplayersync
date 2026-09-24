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


def test_memory_and_tombstone_share_lww():
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/test"

    assert db.apply_changes(con, s, [_chg("k", "0,0,0", 10)]) == {"applied": 1, "skipped_stale": 0}
    assert db.apply_changes(con, s, [_chg("k", "0,0,0", 20, deleted=True)]) == {"applied": 1, "skipped_stale": 0}

    # A newer tombstone blocks an older upsert and an older delete cannot replace it.
    assert db.apply_changes(con, s, [_chg("k", "0,0,0", 15)]) == {"applied": 0, "skipped_stale": 1}
    assert db.apply_changes(con, s, [_chg("k", "0,0,0", 19, deleted=True)]) == {"applied": 0, "skipped_stale": 1}
    assert db.container_count(con, s) == 0
    assert con.execute("SELECT deleted_at FROM tombstones WHERE server_id=? AND key=? AND pos=?",
                       (s, "k", "0,0,0")).fetchone()["deleted_at"] == "2026-09-15T20:00:00Z"

    # A genuinely newer observation re-adds the container and clears the tombstone.
    assert db.apply_changes(con, s, [_chg("k", "0,0,0", 21)]) == {"applied": 1, "skipped_stale": 0}
    assert db.container_count(con, s) == 1
    assert con.execute("SELECT 1 FROM tombstones WHERE server_id=? AND key=? AND pos=?",
                       (s, "k", "0,0,0")).fetchone() is None
    con.close()
    print("memory+tombstone lww ok")


def test_incremental_change_log():
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/revisions"
    db.apply_changes(con, s, [_chg("minecraft:overworld", "0,64,0", 10)])
    db.apply_changes(con, s, [_chg("minecraft:overworld", "1,64,0", 11)])
    db.apply_changes(con, s, [_chg("minecraft:overworld", "0,64,0", 12, deleted=True)])
    assert db.get_revision(con, s) == 3
    changes, tombs = db.select_pull(con, s, since=1)
    assert {c["pos"] for c in changes} == {"1,64,0"}
    assert {t["pos"] for t in tombs} == {"0,64,0"}
    # A cursor at the newest revision is a true no-op.
    assert db.select_pull(con, s, since=3) == ([], [])
    con.close()
    print("incremental change log ok")


def test_range_cursor_does_not_skip_on_move():
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/range-cursor"
    db.apply_changes(con, s, [_chg("minecraft:overworld", "0,64,0", 10),
                               _chg("minecraft:overworld", "9000,64,0", 11)])
    changes, _ = db.select_pull_for_client(con, s, "player", "minecraft:overworld",
                                           0, 64, 0, 5000, None)
    assert {c["pos"] for c in changes} == {"0,64,0"}
    revision = db.get_revision(con, s)
    assert db.select_pull_for_client(con, s, "player", "minecraft:overworld",
                                     0, 64, 0, 5000, revision) == ([], [])
    changes, _ = db.select_pull_for_client(con, s, "player", "minecraft:overworld",
                                           9000, 64, 0, 5000, revision)
    assert {c["pos"] for c in changes} == {"9000,64,0"}
    con.close()
    print("range cursor move ok")

def test_mass_delete_guard():
    assert db.mass_delete_detected(100, 25) is True
    assert db.mass_delete_detected(100, 5) is False
    assert db.mass_delete_detected(0, 0) is False
    assert db.mass_delete_detected(10, 50) is True
    assert db.mass_delete_detected(3, 3) is False
    assert db.mass_delete_detected(9, 9) is False
    assert db.mass_delete_detected(10, 3) is True
    assert db.should_quarantine_mass_delete(100, 25) is True  # alias kept
    print("guard ok")


def test_full_wipe_detector_only_matches_the_whole_bank():
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/full-wipe"
    changes = [_chg("minecraft:overworld", f"{i},64,0", 10) for i in range(10)]
    assert db.apply_changes(con, s, changes)["applied"] == 10

    all_deletes = [_chg(c["key"], c["pos"], 11, deleted=True) for c in changes]
    assert db.full_wipe_detected(con, s, all_deletes, min_bank=10) is True

    partial = all_deletes[:-1]
    assert db.full_wipe_detected(con, s, partial, min_bank=10) is False
    mixed = partial + [_chg(changes[-1]["key"], changes[-1]["pos"], 11)]
    assert db.full_wipe_detected(con, s, mixed, min_bank=10) is False
    assert db.full_wipe_detected(con, s, all_deletes, min_bank=11) is False
    con.close()
    print("full wipe detector ok")


def _chg(key, pos, hour, deleted=False):
    return {"key": key, "pos": pos, "deleted": deleted, "updatedAt": f"2026-09-15T{hour:02d}:00:00Z",
            "updatedBy": "a", "mcVersion": "1.21.11",
            "items": [] if deleted else [{"id": "minecraft:stone", "count": 1}]}


def test_range_gate():
    assert db.in_range("100,64,100", "minecraft:overworld", "minecraft:overworld", 0, 64, 0, 5000) is True
    assert db.in_range("9000,64,0", "minecraft:overworld", "minecraft:overworld", 0, 64, 0, 5000) is False
    assert db.in_range("100,64,100", "minecraft:the_nether", "minecraft:overworld", 0, 64, 0, 5000) is True
    assert db.in_range("99999,64,99999", "chesttracker:ender_chest/aaa", "minecraft:overworld", 0, 64, 0, 5000) is True
    assert db.in_range("bogus", "minecraft:overworld", "minecraft:overworld", 0, 64, 0, 5000) is False
    p = _tmpdb()
    con = db.connect(p)
    s = "multiplayer/test"
    db.apply_changes(con, s, [_chg("minecraft:overworld", "10,64,10", 10),
                              _chg("minecraft:overworld", "9000,64,9000", 10),
                              _chg("minecraft:the_nether", "10,64,10", 10),
                              _chg("chesttracker:ender_chest/aaa", "0,0,0", 10)])
    db.apply_changes(con, s, [_chg("minecraft:overworld", "9001,64,9001", 11, deleted=True)])
    changes, tombs = db.select_pull(con, s, "minecraft:overworld", 0, 64, 0, 5000)
    assert {c["pos"] for c in changes} == {"10,64,10", "0,0,0"}, [c["pos"] for c in changes]
    assert tombs == [], tombs  # far tombstone withheld too
    changes, tombs = db.select_pull(con, s)
    assert len(changes) == 4 and len(tombs) == 1  # legacy ungated pull: everything
    n = db.record_owners(con, s, [_chg("chesttracker:ender_chest/aaa", "0,0,0", 10)], "u1", "Steve")
    assert n == 1 and db.get_owners(con, s) == {"a": "Steve"}  # uuid from change.updatedBy
    n = db.record_owners(con, s, [_chg("minecraft:overworld", "10,64,10", 10)], "u1", "Steve")
    assert n == 0  # world keys never tracked
    con.close()
    print("range+owners ok")


def test_nether_range_gate_uses_overworld_equivalent_distance():
    assert db.in_range("625,64,0", "minecraft:the_nether", "minecraft:the_nether",
                       0, 64, 0, 5000) is True
    assert db.in_range("626,64,0", "minecraft:the_nether", "minecraft:the_nether",
                       0, 64, 0, 5000) is False
    assert db.in_range("625,64,0", "minecraft:the_nether", "minecraft:overworld",
                       0, 64, 0, 5000) is True
    assert db.in_range("626,64,0", "minecraft:the_nether", "minecraft:overworld",
                       0, 64, 0, 5000) is False
    assert db.in_range("5000,64,0", "minecraft:overworld", "minecraft:the_nether",
                       0, 64, 0, 5000) is True
    assert db.in_range("5001,64,0", "minecraft:overworld", "minecraft:the_nether",
                       0, 64, 0, 5000) is False
    assert db.in_range("100,64,0", "minecraft:the_end", "minecraft:overworld",
                       0, 64, 0, 5000) is False
    print("nether range gate ok")

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
    assert db.get_generation(con, s) == 1
    con.close()
    print("snapshot ok")


def test_wipe_challenge_is_shared_and_idempotent():
    p = _tmpdb()
    con1 = db.connect(p)
    con2 = db.connect(p)
    s = "multiplayer/wipe-workers"
    first = db.start_wipe_challenge(con1, s, "worker-a", 9999999999)
    # A second worker must reuse the durable challenge, not replace it.
    second = db.start_wipe_challenge(con2, s, "worker-b", 9999999999)
    assert second["challenge"] == "worker-a"
    assert db.claim_wipe_challenge(con2, s, "worker-a")["state"] == "claimed"
    assert db.claim_wipe_challenge(con1, s, "worker-a")["state"] == "running"
    db.complete_wipe_challenge(con2, s, "worker-a", {"generation": 4, "memories": 0})
    completed = db.claim_wipe_challenge(con1, s, "worker-a")
    assert completed["state"] == "completed"
    assert '"generation": 4' in completed["result"]
    con1.close()
    con2.close()
    print("durable wipe challenge ok")

def test_canonical_case():
    import config
    old_exp, old_alias = config.EXPECTED_SERVER_ID, config.SERVER_ID_ALIASES
    try:
        config.EXPECTED_SERVER_ID = "multiplayer/fabriccraft_net"
        config.SERVER_ID_ALIASES = {"multiplayer/vip_fabriccraft_net"}
        # exact + case variants all resolve to the one canonical bank
        assert config.canonical_server_id("multiplayer/fabriccraft_net") == "multiplayer/fabriccraft_net"
        assert config.canonical_server_id("multiplayer/Fabriccraft_net") == "multiplayer/fabriccraft_net"
        assert config.canonical_server_id("multiplayer/VIP_FABRICCRAFT_NET") == "multiplayer/fabriccraft_net"
        # unknown ids still pass through (then denied)
        assert config.canonical_server_id("multiplayer/other_net") == "multiplayer/other_net"
    finally:
        config.EXPECTED_SERVER_ID, config.SERVER_ID_ALIASES = old_exp, old_alias
    print("canonical case ok")

if __name__ == "__main__":
    test_lww()
    test_memory_and_tombstone_share_lww()
    test_incremental_change_log()
    test_range_cursor_does_not_skip_on_move()
    test_mass_delete_guard()
    test_full_wipe_detector_only_matches_the_whole_bank()
    test_range_gate()
    test_nether_range_gate_uses_overworld_equivalent_distance()
    test_snapshot_restore()
    test_wipe_challenge_is_shared_and_idempotent()
    test_canonical_case()
    print("ALL SERVER TESTS PASSED")
