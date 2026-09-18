package red.jackf.chesttracker.impl.cmsync;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.time.Duration;
import java.util.Optional;

/**
 * /cmsync status | stop | wipealldata [confirm]. Connecting happens in the Memory Bank menu (CMSync tab).
 */
public class CMSyncCommand {
    private CMSyncCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("cmsync")
                        .executes(ctx -> help(ctx.getSource()))
                        .then(ClientCommands.literal("stop")
                                .executes(ctx -> stop(ctx.getSource())))
                        .then(ClientCommands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(ClientCommands.literal("wipealldata")
                                .executes(ctx -> wipeStep1(ctx.getSource()))
                                .then(ClientCommands.literal("confirm")
                                        .executes(ctx -> wipeStep2(ctx.getSource()))))
        ));
    }

    private static int help(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("CMSync: connect in the Memory Bank menu (CMSync tab) | /cmsync status | /cmsync stop | /cmsync wipealldata")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static String gameVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static CMSyncHttp.Identity wipeIdentity(FabricClientCommandSource source,
                                                    red.jackf.jackfredlib.client.api.gps.Coordinate coord) {
        return new CMSyncHttp.Identity(source.getPlayer().getUUID().toString(),
                source.getPlayer().getName().getString(),
                coord.id(), coord.userFriendlyName(),
                gameVersion(), CMSyncManager.MOD_VERSION);
    }

    private static int wipeStep1(FabricClientCommandSource source) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        Optional<Coordinate> coordOpt = Coordinate.getCurrent();
        if (bankOpt.isEmpty() || coordOpt.isEmpty()) {
            source.sendError(Component.literal("Join your server first"));
            return 0;
        }
        CMSyncSettings s = CMSyncSettings.load(bankOpt.get().getId());
        if (!s.isActive()) {
            source.sendError(Component.literal("CMSync not active for this bank"));
            return 0;
        }
        final String url = s.url;
        final String token = s.token;
        CMSyncHttp.Identity ident = wipeIdentity(source, coordOpt.get());
        CMSyncHttp.wipe(url, token, ident, false, null).whenComplete((out, throwable) ->
                source.getClient().execute(() -> {
                    if (throwable != null || out.result() != CMSyncHttp.Result.CONFIRM_REQUIRED) {
                        source.sendError(Component.literal("Wipe aborted: "
                                + (throwable != null ? "connection failed" : out.result() + " " + out.detail())));
                        return;
                    }
                    source.sendFeedback(Component.literal("!!! ABOUT TO WIPE THE SHARED DATABASE !!!")
                            .withStyle(ChatFormatting.RED));
                    source.sendFeedback(Component.literal("This deletes ALL stored item data for this server ("
                            + out.containers() + " containers). Snapshots are kept as backup. "
                            + "Every connected player's local data is cleared too.")
                            .withStyle(ChatFormatting.YELLOW));
                    source.sendFeedback(Component.literal("Type /cmsync wipealldata confirm within 60 seconds to do it.")
                            .withStyle(ChatFormatting.GRAY));
                }));
        return 1;
    }

    private static int wipeStep2(FabricClientCommandSource source) {
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        Optional<Coordinate> coordOpt = Coordinate.getCurrent();
        if (bankOpt.isEmpty() || coordOpt.isEmpty()) {
            source.sendError(Component.literal("Join your server first"));
            return 0;
        }
        CMSyncSettings s = CMSyncSettings.load(bankOpt.get().getId());
        if (!s.isActive()) {
            source.sendError(Component.literal("CMSync not active for this bank"));
            return 0;
        }
        final String bankId = bankOpt.get().getId();
        final String url = s.url;
        final String token = s.token;
        CMSyncHttp.Identity ident = wipeIdentity(source, coordOpt.get());
        CMSyncHttp.wipe(url, token, ident, false, null).whenComplete((first, throwable) ->
                source.getClient().execute(() -> {
                    if (throwable != null || first.result() != CMSyncHttp.Result.CONFIRM_REQUIRED
                            || first.challenge() == null) {
                        source.sendError(Component.literal("Wipe aborted: "
                                + (throwable != null ? "connection failed" : first.result())));
                        return;
                    }
                    CMSyncHttp.wipe(url, token, ident, true, first.challenge()).whenComplete((out, throwable2) ->
                            source.getClient().execute(() -> {
                                if (throwable2 != null || out.result() != CMSyncHttp.Result.WIPED) {
                                    source.sendError(Component.literal("Wipe failed: "
                                            + (throwable2 != null ? "connection failed" : out.result())));
                                    return;
                                }
                                CMSyncManager.INSTANCE.applyServerGeneration(bankId, out.generation());
                                source.sendFeedback(Component.literal("Wiped shared data to zero (generation "
                                        + out.generation() + "). Local data cleared; fresh start.")
                                        .withStyle(ChatFormatting.GREEN));
                            }));
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
        CMSyncManager.INSTANCE.getLastDetail().ifPresent(d ->
                source.sendFeedback(Component.literal(d).withStyle(ChatFormatting.GRAY)));
        int containers = bank.getMemories().values().stream().mapToInt(k -> k.getMemories().size()).sum();
        source.sendFeedback(Component.literal("local containers: " + containers + " in "
                + bank.getMemories().size() + " keys").withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
