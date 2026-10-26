package red.jackf.chesttracker.impl.cmsync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortableItemCodecTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 26.2 binds default item components after the registry bootstrap.
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
                .forEach(pending -> pending.apply());
    }

    private static JsonObject envelope(String items) {
        return JsonParser.parseString("{\"format\":\"cmsync.item\",\"version\":1,\"items\":" + items + "}")
                .getAsJsonObject();
    }

    @Test
    void preservesUnknownComponentsAndItemsAcrossAnEdit() {
        JsonObject original = envelope("""
                [{"slot":0,"id":"minecraft:diamond","count":3,"components":{
                  "future:component":{"op":"set","value":{"items":[{"id":"future:item"}]}}}},
                 {"slot":1,"id":"future:item","count":2,"components":{}}]
                """);
        var decoded = PortableItemCodec.decode(original, JsonOps.INSTANCE);
        assertNotNull(decoded);
        assertEquals(3, decoded.stacks().getFirst().getCount());
        decoded.stacks().getFirst().setCount(5);
        var encoded = PortableItemCodec.encode(decoded.stacks(), decoded.shadow(), JsonOps.INSTANCE);
        assertEquals(original.getAsJsonArray("items").get(0).getAsJsonObject().get("components"),
                encoded.getAsJsonArray("items").get(0).getAsJsonObject().get("components"));
        assertEquals(original.getAsJsonArray("items").get(1), encoded.getAsJsonArray("items").get(1));
        assertEquals(5, encoded.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void resettingAKnownComponentDoesNotResurrectItsShadow() {
        var original = envelope("""
                [{"slot":0,"id":"minecraft:diamond","count":1,"components":{
                  "minecraft:repair_cost":{"op":"set","value":7}}}]
                """);
        var encoded = PortableItemCodec.encode(List.of(new ItemStack(Items.DIAMOND)), original, JsonOps.INSTANCE);
        assertFalse(encoded.getAsJsonArray("items").get(0).getAsJsonObject()
                .getAsJsonObject("components").has("minecraft:repair_cost"));
    }

    @Test
    void explicitRemovalWinsOverForeignShadow() {
        var original = envelope("""
                [{"slot":0,"id":"minecraft:diamond","count":1,"components":{
                  "minecraft:repair_cost":{"op":"set","value":7}}}]
                """);
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.remove(DataComponents.REPAIR_COST);
        var encoded = PortableItemCodec.encode(List.of(stack), original, JsonOps.INSTANCE);
        assertEquals("remove", encoded.getAsJsonArray("items").get(0).getAsJsonObject()
                .getAsJsonObject("components").getAsJsonObject("minecraft:repair_cost").get("op").getAsString());
    }

    @Test
    void invalidComponentDoesNotHideKnownItemOrOtherComponents() {
        var original = envelope("""
                [{"slot":0,"id":"minecraft:diamond","count":2,"components":{
                  "invalid component id":{"op":"set","value":{}},
                  "minecraft:repair_cost":{"op":"set","value":4}}}]
                """);
        var decoded = PortableItemCodec.decode(original, JsonOps.INSTANCE);
        assertNotNull(decoded);
        assertEquals(2, decoded.stacks().getFirst().getCount());
        assertEquals(4, decoded.stacks().getFirst().get(DataComponents.REPAIR_COST));
        var encoded = PortableItemCodec.encode(decoded.stacks(), decoded.shadow(), JsonOps.INSTANCE);
        assertTrue(encoded.getAsJsonArray("items").get(0).getAsJsonObject()
                .getAsJsonObject("components").has("invalid component id"));
    }

    @Test
    void undecodableKnownComponentKeepsItsOriginalJson() {
        var original = envelope("""
                [{"slot":0,"id":"minecraft:diamond","count":1,"components":{
                  "minecraft:repair_cost":{"op":"set","value":{"futureShape":[1,2]}}}}]
                """);
        var decoded = PortableItemCodec.decode(original, JsonOps.INSTANCE);
        assertNotNull(decoded);
        assertFalse(decoded.stacks().getFirst().isEmpty());
        var encoded = PortableItemCodec.encode(decoded.stacks(), decoded.shadow(), JsonOps.INSTANCE);
        assertEquals(original.getAsJsonArray("items").get(0), encoded.getAsJsonArray("items").get(0));
    }

    @Test
    void rejectsUnboundedNegativeAndFractionalSlots() {
        var original = envelope("""
                [{"slot":2147483647,"id":"minecraft:diamond"},
                 {"slot":-1,"id":"minecraft:diamond"},
                 {"slot":0.5,"id":"minecraft:diamond"},
                 {"slot":1,"id":"minecraft:iron_ingot","count":6}]
                """);
        var decoded = PortableItemCodec.decode(original, JsonOps.INSTANCE);
        assertNotNull(decoded);
        assertEquals(2, decoded.stacks().size());
        assertTrue(decoded.stacks().getFirst().isEmpty());
        assertEquals(6, decoded.stacks().get(1).getCount());
    }
}
