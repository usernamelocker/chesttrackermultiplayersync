package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-version helpers. Merge/search on {id,count}; raw components stay opaque.
 * See protocol/normalization.md. Pure + version-tolerant by design.
 */
public final class ItemNormalizer {
    private static final Gson GSON = new Gson();

    private ItemNormalizer() {
    }

    public static String posToString(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static BlockPos parsePos(String s) {
        String[] p = s.split(",");
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    /** ItemStack -> normalized {id,count,componentsDigest}. Skips air/empty (returns null). */
    public static JsonObject toNorm(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("count", Math.max(1, stack.getCount()));
        o.addProperty("componentsDigest", "v1");
        return o;
    }

    /** Normalized list -> ItemStacks (no components on foreign versions; still searchable/countable). */
    public static List<ItemStack> fromNormList(List<JsonObject> norm) {
        List<ItemStack> out = new ArrayList<>();
        for (JsonObject o : norm) {
            try {
                Identifier id = Identifier.parse(o.get("id").getAsString());
                int count = o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1;
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> out.add(new ItemStack(item, count)));
            } catch (RuntimeException ignored) {
                // unknown id from newer version: keep going, don't crash merge
            }
        }
        return out;
    }

    public static String sha256(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public static String hashChanges(List<JsonObject> changes) {
        return sha256(GSON.toJson(changes));
    }
}
