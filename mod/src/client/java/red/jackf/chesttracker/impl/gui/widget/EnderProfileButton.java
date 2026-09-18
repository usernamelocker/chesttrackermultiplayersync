package red.jackf.chesttracker.impl.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * An ender chest profile row: the player's head at the same scale as every other
 * chesttracker icon, with the player name on hover. Offline/unknown players get a
 * plain Steve head — the tooltip always disambiguates.
 */
public class EnderProfileButton extends ItemButton {
    public EnderProfileButton(ItemStack head, int x, int y, OnPress onPress) {
        super(head, x, y, onPress, Background.CUSTOM);
    }

    public static ItemStack headFor(@Nullable UUID uuid) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        if (uuid != null) {
            var conn = Minecraft.getInstance().getConnection();
            var info = conn != null ? conn.getPlayerInfo(uuid) : null;
            if (info != null) {
                try {
                    head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(info.getProfile()));
                } catch (RuntimeException ignored) {
                    // tab-list entry without a usable profile: plain head + tooltip name
                }
            }
        }
        return head;
    }

    public static Component nameTooltip(String name, @Nullable UUID uuid) {
        if (uuid != null && Minecraft.getInstance().player != null
                && uuid.equals(Minecraft.getInstance().player.getUUID()))
            return Component.literal(name + " (you)");
        return Component.literal(name);
    }
}
