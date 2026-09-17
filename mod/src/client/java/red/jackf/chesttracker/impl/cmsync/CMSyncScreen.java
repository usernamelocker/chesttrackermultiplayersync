package red.jackf.chesttracker.impl.cmsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.jackfredlib.client.api.gps.Coordinate;

import java.net.URI;
import java.util.Optional;

/**
 * Simple vanilla setup screen: URL + token boxes, Connect/Stop buttons, status line.
 * Open via {@code /cmsync gui}. No YACL needed, works on 1.21.11 / 26.x.
 *
 * <p>Hook: registered as a client command; no EditMemoryBankScreen edit required for v1.
 * A native tab inside EditMemoryBankScreen can reuse {@link CMSyncSettings} later.
 */
public class CMSyncScreen extends Screen {
    private final String bankId;
    private EditBox urlBox;
    private EditBox tokenBox;
    private String status = "";

    public CMSyncScreen(String bankId) {
        super(Component.literal("CMSync Setup"));
        this.bankId = bankId;
    }

    public static void open() {
        var bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bank.isEmpty()) return;
        Minecraft.getInstance().setScreen(new CMSyncScreen(bank.get().getId()));
    }

    @Override
    protected void init() {
        CMSyncSettings s = CMSyncSettings.load(bankId);
        int cx = this.width / 2;
        this.urlBox = new EditBox(this.font, cx - 150, 60, 300, 20, Component.literal("Server URL"));
        this.urlBox.setMaxLength(256);
        this.urlBox.setValue(s.url != null ? s.url : "http://");
        this.urlBox.setHint(Component.literal("https://cmsync.example.com"));
        this.tokenBox = new EditBox(this.font, cx - 150, 100, 300, 20, Component.literal("Token (optional)"));
        this.tokenBox.setMaxLength(256);
        this.tokenBox.setValue(s.token != null ? s.token : "");
        this.tokenBox.setHint(Component.literal("leave empty if server has no password"));
        this.addRenderableWidget(this.urlBox);
        this.addRenderableWidget(this.tokenBox);

        this.addRenderableWidget(Button.builder(Component.literal("Connect"), b -> onConnect())
                .bounds(cx - 155, 135, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Stop"), b -> onStop())
                .bounds(cx + 5, 135, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx - 75, 165, 150, 20).build());

        CMSyncSettings cur = CMSyncSettings.load(bankId);
        this.status = cur.isActive() ? "active: " + cur.url : "inactive — paste URL, hit Connect";
    }

    private void onConnect() {
        String url = urlBox.getValue().strip();
        String token = tokenBox.getValue().strip();
        URI parsed = CMSyncHttp.parseBaseUrl(url);
        if (parsed == null) {
            status = "bad URL (need http:// or https://)";
            return;
        }
        Optional<MemoryBankImpl> bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        Optional<Coordinate> coordOpt = Coordinate.getCurrent();
        Minecraft mc = Minecraft.getInstance();
        if (bankOpt.isEmpty() || coordOpt.isEmpty() || mc.player == null
                || !bankOpt.get().getId().equals(bankId)) {
            status = "join your server first, then reopen this screen";
            return;
        }
        final String tokenOrNull = token.isEmpty() ? null : token;
        final Coordinate coord = coordOpt.get();
        CMSyncHttp.Identity ident = new CMSyncHttp.Identity(
                mc.player.getUUID().toString(),
                mc.player.getName().getString(),
                coord.id(), coord.userFriendlyName(),
                gameVersion(), CMSyncManager.MOD_VERSION);
        status = "contacting server…";
        // handshake FIRST like /cmsync connect does — only save on SYNCED, so a typo'd
        // URL/token can never silently activate syncing
        CMSyncHttp.handshake(parsed.toString(), tokenOrNull, ident).whenComplete((r, t) ->
                mc.execute(() -> {
                    CMSyncHttp.Result res = t != null ? CMSyncHttp.Result.CONNECTION_FAILED : r;
                    if (res == CMSyncHttp.Result.SYNCED) {
                        Optional<MemoryBankImpl> cur = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
                        if (cur.isEmpty() || !cur.get().getId().equals(bankId)) {
                            status = "world changed — reopen and retry";
                            return;
                        }
                        CMSyncSettings s = CMSyncSettings.load(bankId);
                        s.url = parsed.toString();
                        s.token = tokenOrNull;
                        s.enabled = true;
                        s.paused = false;
                        s.boundServerId = coord.id();
                        s.save(bankId);
                        CMSyncManager.INSTANCE.markActivated(bankId, coord.id());
                        MemoryBankAccessImpl.INSTANCE.save();
                        status = "SYNCED (" + coord.id() + ") — Done to close";
                    } else {
                        status = "failed: " + res + " — check URL/token, retry";
                    }
                }));
    }

    private static String gameVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void onStop() {
        CMSyncSettings s = CMSyncSettings.load(bankId);
        s.forget();
        s.save(bankId);
        CMSyncManager.INSTANCE.deactivate();
        status = "stopped";
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, "CMSync Setup", this.width / 2, 25, 0xFFFFFF);
        g.drawString(this.font, "Server URL:", this.width / 2 - 150, 48, 0xA0A0A0);
        g.drawString(this.font, "Token (password, optional):", this.width / 2 - 150, 88, 0xA0A0A0);
        g.drawCenteredString(this.font, status, this.width / 2, 195, 0xFFFF55);
        g.drawCenteredString(this.font, "Tip: /cmsync status shows serverId for server .env",
                this.width / 2, 210, 0x808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
