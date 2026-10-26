"""Exercise protocol v2 through HTTP for every supported writer/reader pair."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from itertools import product

import pytest
from fastapi.testclient import TestClient

import app
import config


VERSIONS = ("1.21.11", "26.1.2", "26.2")
SID = "multiplayer/version_matrix"
PLAYER = "33333333-3333-3333-3333-333333333333"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DB_PATH", str(tmp_path / "matrix.db"))
    monkeypatch.setattr(config, "EXPECTED_SERVER_ID", SID)
    monkeypatch.setattr(config, "SERVER_ID_ALIASES", set())
    monkeypatch.setattr(config, "WHITELIST_UUIDS", {PLAYER})
    monkeypatch.setattr(config, "SHARED_TOKEN", "")
    app._last_snapshot.pop(SID, None)
    with TestClient(app.app) as client:
        yield client


def identity(version):
    return dict(protocolVersion=2, playerUuid=PLAYER, playerName="Matrix",
                serverId=SID, serverName="Matrix", mcVersion=version,
                modVersion="2.9.0", generation=0)


@pytest.mark.parametrize("writer,reader", list(product(VERSIONS, repeat=2)))
def test_cross_version_observation_raw_and_tombstone(client, writer, reader):
    observed = datetime.now(timezone.utc) - timedelta(minutes=5)
    assert client.post("/api/handshake", json=identity(writer)).json()["status"] == "SYNCED"
    assert client.post("/api/handshake", json=identity(reader)).json()["status"] == "SYNCED"
    raw = {"v": 2, "mc": writer, "memory": {"futureNativeField": [1, 2]},
           "portable": {"format": "cmsync.item", "version": 1, "items": [
               {"slot": 0, "id": "minecraft:shulker_box", "count": 1, "components": {
                   "future:component": {"op": "set", "value": {"nested": [
                       {"id": "future:item", "components": {"future:data": [1, 2, 3]}}
                   ]}}
               }}]},
           "override": {"customName": "Matrix vault", "manualMode": "REMEMBER"}}
    observation = dict(key="minecraft:overworld", pos="1,64,1", deleted=False,
                       updatedAt=observed.isoformat().replace("+00:00", "Z"), updatedBy=PLAYER,
                       mcVersion=writer, items=[{"id": "minecraft:shulker_box", "count": 1}], raw=raw)

    def push(version, change):
        response = client.post("/api/push", json=dict(identity(version),
                               fullHash="matrix", changes=[change]))
        assert response.status_code == 200, response.text
        assert response.json()["status"] == "SYNCED", response.text

    def pull(since=None):
        params = dict(serverId=SID, playerUuid=PLAYER, mcVersion=reader)
        if since is not None:
            params["since"] = since
        response = client.get("/api/pull", params=params)
        assert response.status_code == 200, response.text
        return response.json()

    push(writer, observation)
    initial = pull()
    rows = initial["changes"]
    assert len(rows) == 1
    assert rows[0]["raw"] == raw
    assert rows[0]["mcVersion"] == writer

    stale = deepcopy(observation)
    stale.update(updatedAt=(observed - timedelta(minutes=1)).isoformat().replace("+00:00", "Z"), mcVersion=reader,
                 items=[{"id": "minecraft:diamond", "count": 99}], raw=None)
    push(reader, stale)
    assert pull()["changes"][0]["raw"] == raw

    deletion = dict(observation, deleted=True, updatedAt=(observed + timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
                    mcVersion=reader, items=[], raw=None)
    push(reader, deletion)
    push(writer, observation)  # an offline old peer must not resurrect the row
    result = pull(initial["cursor"])
    assert result["changes"] == []
    assert any(row["pos"] == "1,64,1" for row in result["tombstones"])
    assert pull()["changes"] == []
