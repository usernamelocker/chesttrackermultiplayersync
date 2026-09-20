# Item normalization (cross-version: 1.21.11 / 26.1.2 / 26.2)

Vanilla `ItemStack` codec output changes across MC versions (new components, renamed fields).
Full-bank raw replace breaks across versions. So:

## Rule

* **Index/merge/search on normalized form only:** `{id, count}`.
* **Preserve `raw` per writer version** for same-version fidelity, but never require other versions to parse it.
* Each norm entry carries a `componentsDigest` marker (currently the literal `"v1"`;
  digests are intentionally excluded from equality/hashes — see projection).

```json
{"id": "minecraft:iron_ingot", "count": 64, "componentsDigest": "abc123…"}
```

## Fidelity layer (cmsync.2+): native and portable data inside `raw`

Normalized view alone loses enchantments, custom names, shulker contents. So each
change additionally carries (all inside the server-opaque `raw` blob — no server change):

```json
"raw": {
  "v": 2,
  "mc": "1.21.11",
  "memory": { "...Memory.CODEC JSON: items with full components, name, container..." },
  "portable": {
    "format": "cmsync.item",
    "version": 1,
    "items": [{"slot": 0, "id": "minecraft:shulker_box", "count": 1,
               "components": {"namespace:component": {"op": "set", "value": "codec JSON"}}}]
  },
  "override": { "customName": "Vault", "manualMode": "REMEMBER" }
}
```

* Sender encodes its native `Memory` record (same codec as its own save files) off-thread.
* `portable` is a separately versioned, server-opaque format. It encodes each
  component by registry identifier and that component's codec JSON, so the
  receiving version can decode the components it knows without parsing a
  version-specific `Memory` blob.
* Receiver prefers `portable`, uses native `memory` when available, and falls
  back to names+counts only for old peers without either representation.
* A client keeps the last portable envelope as an item shadow. When it cannot
  decode a component or item, it leaves that JSON untouched in the shadow and
  merges it back into the next push. This preserves unknown/newer components,
  including nested item data, across an older-client edit. A local explicit
  component removal wins over the shadow entry.
* Known nested ItemStack-bearing components are serialized by their own codecs;
  their nested item JSON therefore remains inside the component value. If a
  nested value is unknown or cannot be decoded, the containing component is
  retained opaquely in the shadow rather than partially discarded.
* Existing memories are migrated lazily: the first successful push from an old
  client generates `raw.portable` from its current stacks. Existing server rows
  without the field remain readable through native same-version data or the
  normalized fallback until a client rewrites them.
* **Fallback rule (load-bearing):** the norm view carries no nested NBT (shulker/box
  contents) and no components. A fallback reconstruction MUST keep the observation
  time (`updatedAt`), never stamp `now()` — a fabricated fresh timestamp outranks
  the genuine record on every LWW hop (client, push, server) and permanently wipes
  nested contents everywhere. This exact bug once emptied every synced shulker
  within a minute. Server ties (`stored >= incoming`) keep the stored version,
  which is what makes the stamped fallback safe.
* Change-detection hashes cover identity + items + overrides but NEVER the raw blob
  (same chest encodes to different bytes per MC version — hashing it would flap forever).
* Merge: same-UUID identical echo skipped (protects live entity tracking); otherwise
  last-observation-wins; entity references are always stripped on receive (shared view
  is positional; tracking re-livens on next open); explicit-clear converges removals;
  legacy (v1, no `raw.v`) peers exchange memory only, overrides untouched.
* Entity-held containers (minecarts/boats) are not sent (session-local positions).

## Portable envelope

The envelope is intentionally independent of the network protocol version:

```json
{
  "format": "cmsync.item",
  "version": 1,
  "items": [
    {"slot": 0, "id": "minecraft:diamond", "count": 3,
     "components": {
       "minecraft:custom_data": {"op": "set", "value": {"codec": "JSON"}},
       "minecraft:repair_cost": {"op": "remove"}
     }}
  ]
}
```

`components` is a patch, not a complete map: `set` carries the local
`DataComponentType` codec output and `remove` records an explicit removal.
Unknown component identifiers and values remain opaque JSON in the shadow.
Slots are explicit so an unknown item does not shift later inventory entries.

## Client duties (`ItemNormalizer.java` in overlay)

1. On push: map each `ItemStack` → `{id: registry id, count, componentsDigest}` + keep
   the native `raw.memory` and portable `raw.portable` chunks.
2. On pull: if `raw.mc` equals your MC version, apply `raw.memory` (full restore);
   else build stacks from the normalized list
   (count + id, no components → tooltip shows base item, still searchable).
3. Strip list: never send `minecraft:air` / empty stacks. Clamp count to >=1.
4. Tolerate unknown `id`s from newer versions: keep them, show fallback name, still countable.

## Server duties

* Store both `items_norm` (indexed) and `raw` (opaque JSON).
* `GET /api/view` aggregates on `items_norm` only.
* Never drop fields it doesn't understand.

## Why this works for 1.21.11 ↔ 26.x

Item registry IDs are stable (`minecraft:diamond`, `minecraft:shulker_box`, …).
What breaks is components (enchantments, custom names, `!` flags). Searching/counting by `id`
works everywhere; fancy tooltip detail degrades gracefully on older clients.
