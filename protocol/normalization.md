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
