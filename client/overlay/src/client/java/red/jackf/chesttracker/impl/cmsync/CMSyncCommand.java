package red.jackf.chesttracker.impl.cmsync;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * /cmsync connect &lt;url&gt; [token] | gui | stop | status
 * Token input: chat command (2nd word) OR /cmsync gui screen boxes. No other place.
 */
public class CMSyncCommand {
    private CMSyncCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("cmsync")
                        .then(ClientCommandManager.literal("connect")
                                .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(ctx.getSource(), StringArgumentType.getString(ctx, "url"), null))))
                        .then(ClientCommandManager.literal("gui")
                                .executes(ctx -> gui(ctx.getSource())))
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> stop(ctx.getSource())))
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
        ));
    }

    private static int gui(FabricClientCommandSource source) {
        source.getClient().execute(CMSyncScreen::open);
        return 1;
    }

    private static String gameVersion() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.getGameVersion();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static int connect(FabricClientCommandSource source, String urlRaw, String token) {
        // token as second word inside greedy url: "<url> <token>"
        String url = urlRaw.strip();
        String tok = token;
        String[] parts = url.split("\\s+", 2);
        if (parts.length == 2 && tok == null) {
            url = parts[0];
            tok = parts[1];
        }
        final String finalToken = tok;

        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        Optional<Coordinate> coordOpt = Coordinate.getCurrent();
        if (bankOpt.isEmpty() || coordOpt.isEmpty()) {
            source.sendError(Component.literal("No memory bank loaded (join your server first)"));
            return 0;
        }
        URI parsed = CMSyncHttp.parseBaseUrl(url);
        if (parsed == null) {
            source.sendError(Component.literal("Invalid URL: " + url));
            return 0;
        }
        MemoryBankImpl bank = bankOpt.get();
        final String bankId = bank.getId();
        Coordinate coord = coordOpt.get();
        CMSyncHttp.Identity ident = new CMSyncHttp.Identity(
                source.getPlayer().getUUID().toString(),
                source.getPlayer().getName().getString(),
                coord.id(), coord.userFriendlyName(),
                gameVersion(), "cmsync.1");
        source.sendFeedback(Component.literal("Connecting CMSync → " + parsed).withStyle(ChatFormatting.GRAY));
        CMSyncHttp.handshake(parsed.toString(), finalToken, ident).whenComplete((r, t) ->
                source.getClient().execute(() -> {
                    CMSyncHttp.Result res = t != null ? CMSyncHttp.Result.CONNECTION_FAILED : r;
                    if (res == CMSyncHttp.Result.SYNCED) {
                        Optional<MemoryBankImpl> cur = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
                        if (cur.isEmpty() || !cur.get().getId().equals(bankId)) {
                            source.sendError(Component.literal("World changed during connect"));
                            return;
                        }
                        CMSyncSettings s = CMSyncSettings.load(bankId);
                        s.url = parsed.toString();
                        s.token = finalToken;
                        s.enabled = true;
                        s.paused = false;
                        s.boundServerId = coord.id();
                        s.save(bankId);
                        CMSyncManager.INSTANCE.markActivated(bankId, coord.id());
                        MemoryBankAccessImpl.INSTANCE.save();
                        source.sendFeedback(Component.literal("CMSync SYNCED (" + coord.id() + ")").withStyle(ChatFormatting.GREEN));
                    } else {
                        source.sendError(Component.literal("CMSync failed: " + res));
                    }
                }));
        return 1;
    }

    private static int stop(FabricClientCommandSource source) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty()) {
            source.sendError(Component.literal("No bank loaded"));
            return 0;
        }
        CMSyncSettings s = CMSyncSettings.load(bankOpt.get().getId());
        if (!s.isConnected()) {
            source.sendError(Component.literal("CMSync not active"));
            return 0;
        }
        s.forget();
        s.save(bankOpt.get().getId());
        CMSyncManager.INSTANCE.deactivate();
        MemoryBankAccessImpl.INSTANCE.save();
        source.sendFeedback(Component.literal("CMSync stopped").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty()) {
            source.sendError(Component.literal("No bank loaded"));
            return 0;
        }
        MemoryBankImpl bank = bankOpt.get();
        CMSyncSettings s = CMSyncSettings.load(bank.getId());
        source.sendFeedback(Component.literal("— CMSync status —").withStyle(ChatFormatting.GOLD));
        if (s.isActive()) source.sendFeedback(Component.literal("active → " + s.url).withStyle(ChatFormatting.GREEN));
        else if (s.isConnected()) source.sendFeedback(Component.literal("paused → " + s.url).withStyle(ChatFormatting.YELLOW));
        else source.sendFeedback(Component.literal("inactive").withStyle(ChatFormatting.GRAY));
        Coordinate.getCurrent().ifPresent(c -> source.sendFeedback(Component.literal(
                "server: " + c.userFriendlyName() + " [" + c.id() + "]").withStyle(ChatFormatting.GRAY)));
        source.sendFeedback(Component.literal("bound: " + s.boundServerId + " | bank: " + bank.getId()).withStyle(ChatFormatting.GRAY));
        source.sendFeedback(Component.literal("player: " + source.getPlayer().getName().getString()
                + " " + source.getPlayer().getUUID()).withStyle(ChatFormatting.GRAY));
        CMSyncManager.INSTANCE.getLastSuccess().ifPresentOrElse(
                t -> source.sendFeedback(Component.literal("last sync: "
                        + Duration.between(t, java.time.Instant.now()).toSeconds() + "s ago ("
                        + CMSyncManager.INSTANCE.getLastResult().orElse("?") + ")").withStyle(ChatFormatting.GREEN)),
                () -> source.sendFeedback(Component.literal("never synced ("
                        + CMSyncManager.INSTANCE.getLastResult().orElse("-") + ")").withStyle(ChatFormatting.YELLOW)));
        int containers = bank.getMemories().values().stream().mapToInt(k -> k.getMemories().size()).sum();
        source.sendFeedback(Component.literal("local containers: " + containers + " in "
                + bank.getMemories().size() + " keys").withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
