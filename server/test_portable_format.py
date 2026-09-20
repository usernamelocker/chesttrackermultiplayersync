"""Protocol-level tests for the portable item envelope.

The game-side codec is compiled against each supported Minecraft mapping. These
tests exercise the format contract that must survive a client which cannot
interpret a newer item or component: known local values may be rewritten, but
foreign JSON is merged back from the previous shadow unchanged.
"""
from copy import deepcopy


def _older_client_reencode(previous, local_items):
    """Model the codec's preservation rule for a client with a smaller registry."""
    old_by_slot = {item["slot"]: item for item in previous["items"]}
    out = {"format": previous["format"], "version": previous["version"], "items": []}
    emitted = set()
    local_ids = {item["id"] for item in local_items if item}
    for slot, item in enumerate(local_items):
        if item is None:
            continue
        rewritten = deepcopy(item)
        old = old_by_slot.get(slot)
        if old and old["id"] == item["id"]:
            merged = deepcopy(old.get("components", {}))
            merged.update(item.get("components", {}))
            rewritten["components"] = merged
        out["items"].append(rewritten)
        emitted.add(slot)
    for slot, old in old_by_slot.items():
        if slot not in emitted and old["id"] not in local_ids:
            out["items"].append(deepcopy(old))
    return out


def test_foreign_component_and_nested_json_round_trip():
    nested_foreign = {
        "slot": 0,
        "id": "minecraft:shulker_box",
        "count": 1,
        "components": {
            "minecraft:custom_name": {"op": "set", "value": "Vault"},
            "minecraft:future_component": {
                "op": "set",
                "value": {
                    "nestedItems": [{"id": "minecraft:diamond", "count": 3,
                                      "components": {"futureNested": {"x": 1}}}]
                },
            },
        },
    }
    newer = {"format": "cmsync.item", "version": 1,
             "items": [nested_foreign, {
                 "slot": 1, "id": "minecraft:future_item", "count": 1,
                 "components": {"minecraft:future_component": {"op": "set", "value": {"x": 2}}},
             }]}

    # The older client understands the shulker and custom name, but not the
    # future component or future item. Its local view has an empty slot 1.
    older_local = [{"slot": 0, "id": "minecraft:shulker_box", "count": 1,
                    "components": {"minecraft:custom_name": {"op": "set", "value": "Edited"}}}, None]
    round_tripped = _older_client_reencode(newer, older_local)

    slot0 = next(item for item in round_tripped["items"] if item["slot"] == 0)
    assert slot0["components"]["minecraft:future_component"] == \
        nested_foreign["components"]["minecraft:future_component"]
    assert slot0["components"]["minecraft:future_component"]["value"]["nestedItems"][0]["components"] == \
        {"futureNested": {"x": 1}}
    slot1 = next(item for item in round_tripped["items"] if item["slot"] == 1)
    assert slot1 == newer["items"][1]
    assert slot0["components"]["minecraft:custom_name"]["value"] == "Edited"
