package red.jackf.chesttracker.impl.cmsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;

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
        if (CMSyncHttp.parseBaseUrl(url) == null) {
            status = "bad URL (need http:// or https://)";
            return;
        }
        CMSyncSettings s = CMSyncSettings.load(bankId);
        s.url = url;
        s.token = token.isEmpty() ? null : token;
        s.enabled = true;
        s.paused = false;
        // boundServerId filled on next tick from Coordinate; set loosely here
        s.save(bankId);
        MemoryBankAccessImpl.INSTANCE.save();
        status = "saved — handshake runs on next tick (/cmsync status to confirm)";
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
