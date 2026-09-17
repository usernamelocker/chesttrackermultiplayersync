# Item normalization (cross-version: 1.21.11 / 26.1.2 / 26.2)

Vanilla `ItemStack` codec output changes across MC versions (new components, renamed fields).
Full-bank raw replace breaks across versions. So:

## Rule

* **Index/merge/search on normalized form only:** `{id, count}`.
* **Preserve `raw` per writer version** for same-version fidelity, but never require other versions to parse it.
* **Digest components** for change detection without coupling: `componentsDigest = sha256(canonical(raw.components))`.

```json
{"id": "minecraft:iron_ingot", "count": 64, "componentsDigest": "abc123…"}
```

## Fidelity layer (cmsync.2+): full NBT inside `raw`

Normalized view alone loses enchantments, custom names, shulker contents. So each
change additionally carries (all inside the server-opaque `raw` blob — no server change):

```json
"raw": {
  "v": 2,
  "mc": "1.21.11",
  "memory": { "...Memory.CODEC JSON: items with full components, name, container..." },
  "override": { "customName": "Vault", "manualMode": "REMEMBER" }
}
```

* Sender encodes its native `Memory` record (same codec as its own save files) off-thread.
* Receiver restores everything **iff `raw.mc` equals its own MC version**, else names+counts fallback.
* Change-detection hashes cover identity + items + overrides but NEVER the raw blob
  (same chest encodes to different bytes per MC version — hashing it would flap forever).
* Merge: same-UUID identical echo skipped (protects live entity tracking); otherwise
  last-observation-wins; entity references are always stripped on receive (shared view
  is positional; tracking re-livens on next open); explicit-clear converges removals;
  legacy (v1, no `raw.v`) peers exchange memory only, overrides untouched.
* Entity-held containers (minecarts/boats) are not sent (session-local positions).

## Client duties (`ItemNormalizer.java` in overlay)

1. On push: map each `ItemStack` → `{id: registry id, count, componentsDigest}` + keep `raw` chunk from `DATA_CODEC`.
2. On pull: if entry `mcVersion == mine`, apply `raw` if present; else build stacks from normalized list
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
