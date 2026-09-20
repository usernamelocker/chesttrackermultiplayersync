package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Version-neutral item/component envelope used by CMSync.  The server stores this
 * JSON opaquely; only the client interprets it with its own registries/codecs.
 */
public final class PortableItemCodec {
    public static final String FORMAT = "cmsync.item";
    public static final int VERSION = 1;

    private PortableItemCodec() {
    }

    public record Decoded(List<ItemStack> stacks, JsonObject shadow) {
    }

    public static JsonObject encode(List<ItemStack> stacks, @Nullable JsonObject previous,
                                    DynamicOps<JsonElement> ops) {
        JsonObject out = new JsonObject();
        out.addProperty("format", FORMAT);
        out.addProperty("version", VERSION);
        JsonArray items = new JsonArray();
        Map<Integer, JsonObject> oldBySlot = oldItems(previous);
        Map<Integer, Boolean> emittedSlots = new HashMap<>();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) throw new IllegalStateException("unregistered item " + stack.getItem());
            item.addProperty("slot", slot);
            item.addProperty("id", itemId.toString());
            item.addProperty("count", stack.getCount());
            JsonObject components = encodeComponents(stack, ops);
            JsonObject old = oldBySlot.get(slot);
            if (old != null && itemId.toString().equals(optString(old, "id"))) {
                JsonObject oldComponents = old.has("components") && old.get("components").isJsonObject()
                        ? old.getAsJsonObject("components") : null;
                if (oldComponents != null) {
                    for (var e : oldComponents.entrySet()) {
                        // An older client cannot have an entry for a foreign type in
                        // its ItemStack patch. Preserve such entries until a client
                        // that understands them can decode them.
                        if (!components.has(e.getKey())) components.add(e.getKey(), e.getValue().deepCopy());
                    }
                }
            }
            item.add("components", components);
            items.add(item);
            emittedSlots.put(slot, true);
        }
        // An older client cannot materialize an item whose registry id is unknown.
        // Keep that whole item opaque rather than turning it into a deletion.
        for (var old : oldBySlot.entrySet()) {
            if (emittedSlots.containsKey(old.getKey())) continue;
            try {
                Identifier oldId = Identifier.parse(old.getValue().get("id").getAsString());
                if (BuiltInRegistries.ITEM.getOptional(oldId).isEmpty()) items.add(old.getValue().deepCopy());
            } catch (RuntimeException ignored) {
            }
        }
        out.add("items", items);
        return out;
    }

    @Nullable
    public static Decoded decode(@Nullable JsonObject envelope, DynamicOps<JsonElement> ops) {
        if (!isEnvelope(envelope)) return null;
        List<ItemStack> stacks = new ArrayList<>();
        JsonArray items = envelope.getAsJsonArray("items");
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            try {
                int slot = Math.max(0, item.get("slot").getAsInt());
                while (stacks.size() <= slot) stacks.add(ItemStack.EMPTY);
                Identifier id = Identifier.parse(item.get("id").getAsString());
                ItemStack stack = BuiltInRegistries.ITEM.getOptional(id).map(value ->
                        new ItemStack(value, Math.max(1, item.has("count") ? item.get("count").getAsInt() : 1))
                ).orElse(null);
                if (stack == null) continue;
                DataComponentPatch.Builder patch = DataComponentPatch.builder();
                if (item.has("components") && item.get("components").isJsonObject()) {
                    for (var e : item.getAsJsonObject("components").entrySet()) {
                        Identifier componentId = Identifier.parse(e.getKey());
                        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE
                                .getOptional(componentId).orElse(null);
                        if (type == null) continue; // retained in the shadow envelope
                        JsonObject entry = e.getValue().isJsonObject()
                                ? e.getValue().getAsJsonObject() : null;
                        if (entry == null) continue;
                        String op = optString(entry, "op");
                        if ("remove".equals(op)) {
                            removeComponent(patch, type);
                        } else if ("set".equals(op) && entry.has("value")) {
                            DataResult<?> decoded = type.codec().parse(ops, entry.get("value"));
                            if (decoded.result().isPresent()) setComponent(patch, type, decoded.result().get());
                        }
                    }
                }
                stack.applyComponents(patch.build());
                stacks.set(slot, stack);
            } catch (RuntimeException ignored) {
                // Keep the original item in the shadow. Normalized id/count data
                // remains available to the caller if this local registry cannot decode it.
            }
        }
        return new Decoded(stacks, envelope.deepCopy());
    }

    public static boolean isEnvelope(@Nullable JsonObject envelope) {
        if (envelope == null || !envelope.has("format") || !envelope.has("version") || !envelope.has("items")) return false;
        try {
            return FORMAT.equals(envelope.get("format").getAsString())
                    && envelope.get("version").getAsInt() == VERSION
                    && envelope.get("items").isJsonArray();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static JsonObject encodeComponents(ItemStack stack, DynamicOps<JsonElement> ops) {
        JsonObject components = new JsonObject();
        for (var entry : stack.getComponentsPatch().entrySet()) {
            Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
            if (id == null) throw new IllegalStateException("unregistered component type");
            JsonObject encoded = new JsonObject();
            if (entry.getValue().isPresent()) {
                Object value = entry.getValue().get();
                DataResult<JsonElement> result = encodeComponent(entry.getKey(), value, ops);
                JsonElement json = result.result().orElseThrow(() ->
                        new IllegalStateException("cannot encode component " + id + ": "
                                + result.error().map(Object::toString).orElse("unknown error")));
                encoded.addProperty("op", "set");
                encoded.add("value", json);
            } else {
                encoded.addProperty("op", "remove");
            }
            components.add(id.toString(), encoded);
        }
        return components;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataResult<JsonElement> encodeComponent(DataComponentType type, Object value,
                                                            DynamicOps<JsonElement> ops) {
        return type.codec().encodeStart(ops, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setComponent(DataComponentPatch.Builder builder, DataComponentType type, Object value) {
        builder.set(type, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeComponent(DataComponentPatch.Builder builder, DataComponentType type) {
        builder.remove(type);
    }

    private static Map<Integer, JsonObject> oldItems(@Nullable JsonObject previous) {
        Map<Integer, JsonObject> result = new HashMap<>();
        if (!isEnvelope(previous)) return result;
        for (JsonElement element : previous.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            try {
                JsonObject item = element.getAsJsonObject();
                result.put(item.get("slot").getAsInt(), item);
            } catch (RuntimeException ignored) {
            }
        }
        return result;
    }

    @Nullable
    private static String optString(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
