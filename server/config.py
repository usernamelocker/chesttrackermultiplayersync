"""Env config. Real env vars take precedence over .env file (local dev fallback)."""
from __future__ import annotations
import os
from pathlib import Path

def _load_dotenv() -> None:
    env_file = Path(__file__).parent / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())

_load_dotenv()

def _CSV(name: str) -> set[str]:
    raw = os.environ.get(name, "")
    return {p.strip() for p in raw.split(",") if p.strip()}

EXPECTED_SERVER_ID = os.environ.get("EXPECTED_SERVER_ID", "")
# Extra addresses for the SAME proxy/network, merged into the canonical bank above.
# e.g. players joining via fabriccraft.net AND vip.fabriccraft.net share one bank.
SERVER_ID_ALIASES: set[str] = _CSV("SERVER_ID_ALIASES")


def canonical_server_id(server_id: str) -> str:
    """Map a known alias to the canonical id; unknown ids pass through (then denied).

    Case-insensitive: different client versions report the same server with
    different capitalisation (multiplayer/Fabriccraft_net vs
    multiplayer/fabriccraft_net). All spellings resolve to the single canonical
    id so they share one bank instead of 403ing.
    """
    if EXPECTED_SERVER_ID and server_id.lower() == EXPECTED_SERVER_ID.lower():
        return EXPECTED_SERVER_ID
    for alias in SERVER_ID_ALIASES:
        if server_id.lower() == alias.lower():
            return EXPECTED_SERVER_ID
    return server_id
WHITELIST_UUIDS: set[str] = _CSV("WHITELIST_UUIDS")
SHARED_TOKEN = os.environ.get("SHARED_TOKEN", "")
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "") or SHARED_TOKEN
DB_PATH = os.environ.get("DB_PATH", "data/cmsync.db")
SNAPSHOT_INTERVAL_MIN = int(os.environ.get("SNAPSHOT_INTERVAL_MIN", "15"))
SNAPSHOT_KEEP = int(os.environ.get("SNAPSHOT_KEEP", "96"))
MAX_DELETE_FRACTION = float(os.environ.get("MAX_DELETE_FRACTION", "0.20"))
MAX_DELETE_COUNT = int(os.environ.get("MAX_DELETE_COUNT", "50"))
MIN_QUARANTINE_BANK = int(os.environ.get("MIN_QUARANTINE_BANK", "10"))
# A full-bank delete is held only when the bank is at least this large. This
# protects established banks without interfering with intentional cleanup of a
# small test bank or a partial delete.
FULL_WIPE_MIN_BANK = int(os.environ.get("FULL_WIPE_MIN_BANK", "10"))
# Range gate: pull only returns same-dimension containers within this many
# Overworld-equivalent blocks of the requesting player (+ ender-style keys,
# which have no position). Nether horizontal coordinates use the 1:8 portal
# scale, so 5000 means 625 Nether blocks by default.
RANGE_BLOCKS = int(os.environ.get("RANGE_BLOCKS", "5000"))
# Client observation timestamps may be old because clients can be offline, but
# a far-future timestamp could permanently win LWW for a container.
MAX_UPDATE_FUTURE_SECONDS = int(os.environ.get("MAX_UPDATE_FUTURE_SECONDS", "300"))
TOMBSTONE_TTL_DAYS = int(os.environ.get("TOMBSTONE_TTL_DAYS", "30"))
