"""Pydantic v2 models for CMSync v2."""
from __future__ import annotations
from datetime import datetime, timedelta, timezone
import re
from typing import Any, Optional
from pydantic import BaseModel, Field, field_validator

import config

_UTC_TIMESTAMP = re.compile(
    r"^(?P<base>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})"
    r"(?:\.(?P<fraction>\d{1,9}))?Z$"
)


def _canonical_timestamp(value: str) -> str:
    if not isinstance(value, str):
        raise ValueError("updatedAt must be a UTC timestamp")
    match = _UTC_TIMESTAMP.fullmatch(value)
    if not match:
        raise ValueError("updatedAt must be an ISO-8601 UTC timestamp ending in Z")

    base = match.group("base")
    fraction = match.group("fraction") or ""
    try:
        # datetime validates calendar/time ranges. Python only needs the first
        # six fractional digits for the future-skew check; the canonical value
        # below preserves all nine digits accepted from Java Instant.
        parsed = datetime.fromisoformat(base + ("." + fraction[:6] if fraction else ""))
    except ValueError as exc:
        raise ValueError("updatedAt is not a valid UTC timestamp") from exc
    parsed = parsed.replace(tzinfo=timezone.utc)
    if parsed > datetime.now(timezone.utc) + timedelta(seconds=config.MAX_UPDATE_FUTURE_SECONDS):
        raise ValueError("updatedAt is too far in the future")
    return f"{base}.{fraction.ljust(9, '0')}Z"

class Identity(BaseModel):
    protocolVersion: int = 2
    playerUuid: str
    playerName: str
    serverId: str
    serverName: str = ""
    mcVersion: str = "unknown"
    modVersion: str = "unknown"

class NormItem(BaseModel):
    id: str
    count: int = Field(ge=1)
    componentsDigest: Optional[str] = None

class Change(BaseModel):
    key: str
    pos: str  # "x,y,z"
    deleted: bool = False
    updatedAt: str
    updatedBy: Optional[str] = None
    mcVersion: str = "unknown"
    items: list[NormItem] = []
    raw: Optional[Any] = None

    @field_validator("updatedAt")
    @classmethod
    def validate_updated_at(cls, value: str) -> str:
        return _canonical_timestamp(value)

class PushRequest(Identity):
    baseHash: Optional[str] = None
    fullHash: str = ""
    # Every v2 push must echo the generation learned during handshake/pull.
    # Omitting it is rejected so old clients fail safely instead of resurrecting
    # data after an offline wipe.
    generation: int = Field(..., ge=0)
    changes: list[Change] = []

class HandshakeRequest(Identity):
    pass
