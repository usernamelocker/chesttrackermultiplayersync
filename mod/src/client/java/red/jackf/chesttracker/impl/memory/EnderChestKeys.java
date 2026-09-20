package red.jackf.chesttracker.impl.memory;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryKey;
import red.jackf.chesttracker.api.memory.counting.CountingPredicate;
import red.jackf.chesttracker.api.memory.counting.StackMergeMode;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.compat.servers.hypixel.HypixelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ender chests (and other personal inventories: hypixel backpacks/sacks/vaults) are
 * recorded per player: {@code <base>/<uuid>} instead of one shared slot. Without this,
 * every synced player's ender chest lands in the same key+position and mixes.
 *
 * <p>Third-party keys (share-ender-chest compat) are left untouched.
 */
public final class EnderChestKeys {
    private EnderChestKeys() {
    }

    /** Own ender chest key, or the legacy shared key when no player is available. */
    public static Identifier ownKey() {
        var player = Minecraft.getInstance().player;
        return player != null ? ownKey(player.getUUID()) : CommonKeys.ENDER_CHEST_KEY;
    }

    public static Identifier ownKey(UUID uuid) {
        return ChestTracker.id("ender_chest/" + uuid);
    }

    public static Identifier ownSkyblockKey(UUID uuid) {
        return Identifier.fromNamespaceAndPath("hypixel", "skyblock_ender_chest/" + uuid);
    }

    public static Identifier ownHypixelKey(Identifier base, UUID uuid) {
        return Identifier.fromNamespaceAndPath(base.getNamespace(), base.getPath() + "/" + uuid);
    }

    /** Own namespaced variant of a personal Hypixel key, or the base key if unavailable. */
    public static Identifier ownHypixelKey(Identifier base) {
        var player = Minecraft.getInstance().player;
        return player != null ? ownHypixelKey(base, player.getUUID()) : base;
    }

    /** Keys rendered as per-player profiles (own + teammates), not as world containers. */
    public static boolean isProfileKey(Identifier key) {
        String ns = key.getNamespace();
        String path = key.getPath();
        if (ns.equals("chesttracker") && (path.equals("ender_chest") || path.startsWith("ender_chest/")))
            return true;
        return ns.equals("hypixel") && (path.equals("skyblock_ender_chest")
                || path.startsWith("skyblock_ender_chest/"));
    }

    /**
     * Exact legacy shared keys (pre-per-player era). Fully hidden: the migration
     * absorbs them into the owner's key on load, the viewer skips them, readers
     * ignore them, and sync neither sends nor applies them.
     */
    public static boolean isHiddenLegacyKey(Identifier key) {
        return key.equals(CommonKeys.ENDER_CHEST_KEY)
                || key.equals(HypixelProvider.SKYBLOCK_ENDER_CHEST)
                || key.equals(HypixelProvider.SKYBLOCK_BACKBACKS)
                || key.equals(HypixelProvider.SKYBLOCK_SACKS)
                || key.equals(HypixelProvider.SKYBLOCK_VAULT);
    }

    /** Keys the ender-chest sync toggle covers (profiles + legacy + share-mod compat). */
    public static boolean isSyncableEnderKey(Identifier key) {
        return isProfileKey(key) || key.equals(CommonKeys.SHARE_ENDER_CHEST);
    }

    /** Owner of a per-player key, if the trailing path segment parses as a UUID. */
    public static Optional<UUID> ownerUuid(Identifier key) {
        String path = key.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(path.substring(slash + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * One-time move of legacy shared ender chest data into the player's own key.
     * Runs on bank load; skips positions the own key already has.
     */
    public static void migrateBank(MemoryBankImpl bank, @Nullable UUID ownUuid) {
        if (ownUuid == null) return;
        moveKey(bank, CommonKeys.ENDER_CHEST_KEY, ownKey(ownUuid));
        moveKey(bank, HypixelProvider.SKYBLOCK_ENDER_CHEST, ownHypixelKey(HypixelProvider.SKYBLOCK_ENDER_CHEST, ownUuid));
        moveKey(bank, HypixelProvider.SKYBLOCK_BACKBACKS, ownHypixelKey(HypixelProvider.SKYBLOCK_BACKBACKS, ownUuid));
        moveKey(bank, HypixelProvider.SKYBLOCK_SACKS, ownHypixelKey(HypixelProvider.SKYBLOCK_SACKS, ownUuid));
        moveKey(bank, HypixelProvider.SKYBLOCK_VAULT, ownHypixelKey(HypixelProvider.SKYBLOCK_VAULT, ownUuid));
    }

    private static void moveKey(MemoryBankImpl bank, Identifier from, Identifier to) {
        if (from.equals(to)) return;
        Optional<MemoryKeyImpl> fromKey = bank.getKeyInternal(from);
        if (fromKey.isEmpty() || fromKey.get().getMemories().isEmpty()) return;
        MemoryKeyImpl toKey = bank.getOrCreateKeyInternal(to);
        int moved = 0;
        for (var entry : new ArrayList<>(fromKey.get().getMemories().entrySet())) {
            if (toKey.getMemories().containsKey(entry.getKey())) continue;
            toKey.getMemories().put(entry.getKey(), entry.getValue());
            entry.getValue().populate(toKey, entry.getKey());
            var override = fromKey.get().overrides().remove(entry.getKey());
            if (override != null) toKey.overrides().put(entry.getKey(), override);
            moved++;
        }
        if (moved > 0) bank.markMutated();
        if (!fromKey.get().getMemories().isEmpty()) {
            // anything left (collisions) stays; only drop the key when fully moved
            if (moved > 0) ChestTracker.LOGGER.info("Migrated {} ender chest entries {} -> {}", moved, from, to);
            return;
        }
        bank.removeKey(from);
        ChestTracker.LOGGER.info("Migrated ender chest {} -> {}", from, to);
    }

    /** Own ender chest contents for integrations (preview, material lists). */
    public static Optional<MemoryKey> getOwn(MemoryBank bank) {
        return bank.getKey(ownKey()).filter(key -> !key.isEmpty());
    }

    /** Own ender chest contents (own key only — migration absorbs legacy on load). */
    public static List<net.minecraft.world.item.ItemStack> getOwnCounts(MemoryBank bank,
                                                                         CountingPredicate predicate,
                                                                         StackMergeMode stackMergeMode,
                                                                         boolean unpackNested) {
        List<net.minecraft.world.item.ItemStack> out = new ArrayList<>();
        bank.getKey(ownKey()).ifPresent(key -> out.addAll(key.getCounts(predicate, stackMergeMode, unpackNested)));
        return out;
    }
}
