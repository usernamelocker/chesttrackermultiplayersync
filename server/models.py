"""Pydantic v2 models for CMSync v2."""
from __future__ import annotations
from typing import Any, Optional
from pydantic import BaseModel, Field

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
