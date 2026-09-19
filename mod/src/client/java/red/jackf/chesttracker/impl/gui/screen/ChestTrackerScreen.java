package red.jackf.chesttracker.impl.gui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.cmsync.CMSyncManager;
import red.jackf.chesttracker.impl.memory.EnderChestKeys;
import red.jackf.chesttracker.api.memory.counting.CountingPredicate;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.chesttracker.impl.compat.Compatibility;
import red.jackf.chesttracker.impl.compat.mods.searchables.SearchablesUtil;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.config.ChestTrackerConfigScreenBuilder;
import red.jackf.chesttracker.impl.gui.GuiConstants;
import red.jackf.chesttracker.impl.gui.util.TextColours;
import red.jackf.chesttracker.impl.gui.widget.*;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.util.GuiUtil;
import red.jackf.chesttracker.impl.util.ItemStacks;
import red.jackf.chesttracker.impl.util.Misc;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;

import static net.minecraft.network.chat.Component.translatable;

/**
 * The main screen
 */
public class ChestTrackerScreen extends Screen {
    private static final Component TITLE = translatable("chesttracker.title");
    private static final int TITLE_LEFT = 8;
    private static final int TITLE_TOP = 8;
    private static final int SEARCH_LEFT = 8;
    private static final int SEARCH_TOP = 27;
    private static final int GRID_LEFT = 7;
    private static final int GRID_TOP = 41;
    private static final int BUTTON_SIZE = 14;
    private static final int MEMORY_ICON_OFFSET = 24;
    private static final int MEMORY_ICON_SPACING = 24;
    private static final int SMALL_MENU_WIDTH = 192;
    private static final int SMALL_MENU_HEIGHT = 156;
    private boolean initializedOnce = false;

    private static ContainerFilter containerFilter = ContainerFilter.ALL;
    private static ItemSort itemSort = ItemSort.COUNT_DESCENDING;
    private final Screen parent;
    private int left = 0;
    private int top = 0;
    private int menuWidth;
    private int menuHeight;
    // borrowed from creative screen, pressing `t` to focus search also triggers an input on charTyped
    private boolean ignoreTextInput = false;
    private EditBox search;
    private ItemListWidget itemList;
    @Nullable
    private ResizeWidget resize = null;
    private VerticalScrollWidget scroll;
    private Identifier currentMemoryKey;
    private List<ItemStack> items = Collections.emptyList();
    // ender chest profiles (one shared icon + per-player buttons in a second column)
    @Nullable
    private ItemButton echestButton = null;
    private final List<EnderProfileButton> profileRows = new ArrayList<>();
    private final List<Profile> profiles = new ArrayList<>();
    private boolean dropdownOpen = false;
    private Set<Identifier> lastSeenKeys = new HashSet<>();
    private static String preservedQuery = "";
    @Nullable
    private static Identifier preservedKey = null;
    private static boolean restorePending = false;

    private record Profile(Identifier key, @Nullable UUID uuid, String name) {
    }

    public ChestTrackerScreen(@Nullable Screen parent) {
        super(TITLE);
        ChestTracker.LOGGER.debug("Open Screen");
        this.parent = parent;
        this.currentMemoryKey = ProviderUtils.getPlayersCurrentKey()
                .orElseGet(() -> ChestTracker.id("unknown"));
    }

    @Override
    protected void init() {
        MemoryBankImpl bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal().orElse(null);
        if (bank == null) {
            if (!initializedOnce) {
                initializedOnce = true;
                Minecraft.getInstance().setScreen(new MemoryBankManagerScreen(parent, () -> new ChestTrackerScreen(this)));
            } else {
                this.onClose();
            }
            return;
        }
        initializedOnce = true;
        this.lastSeenKeys = new HashSet<>(bank.getKeys());
        if (restorePending) {
            restorePending = false;
            if (preservedKey != null && bank.getKeys().contains(preservedKey))
                this.currentMemoryKey = preservedKey;
        }
        var config = ChestTrackerConfig.INSTANCE.instance();
        var liveGridWidth = config.gui.gridWidth + 1;
        var liveGridHeight = config.gui.gridHeight + 1;

        // shrink until fits on screen
        do
            this.menuWidth = SMALL_MENU_WIDTH + (--liveGridWidth - 9) * GuiConstants.GRID_SLOT_SIZE;
        while (this.menuWidth > width && liveGridWidth > GuiConstants.MIN_GRID_COLUMNS);
        do
            this.menuHeight = SMALL_MENU_HEIGHT + (--liveGridHeight - 6) * GuiConstants.GRID_SLOT_SIZE;
        while (this.menuHeight > height && liveGridHeight > GuiConstants.MIN_GRID_ROWS);

        this.left = (this.width - menuWidth) / 2;
        this.top = (this.height - menuHeight) / 2;

        super.init();

        // items
        this.itemList = new ItemListWidget(left + GRID_LEFT, top + GRID_TOP, liveGridWidth, liveGridHeight);

        // scroll
        this.scroll = this.addRenderableWidget(new VerticalScrollWidget(left + menuWidth - 19, top + GRID_TOP, this.itemList.getHeight(), Component.empty()));
        this.scroll.setResponder(this.itemList::onScroll);

        this.addRenderableWidget(this.itemList);

        // search
        var shouldFocusSearch = this.search == null || this.search.isFocused();
        shouldFocusSearch &= config.gui.autofocusSearchBar;
        if (config.gui.showAutocomplete && Compatibility.SEARCHABLES) {
            this.search = addRenderableWidget(SearchablesUtil.getEditBox(
                    font,
                    left + SEARCH_LEFT,
                    top + SEARCH_TOP,
                    menuWidth - 16,
                    12,
                    this.search,
                    () -> items,
                    this::filter
            ));
        } else {
            this.search = addRenderableWidget(new CustomEditBox(
                    font,
                    left + SEARCH_LEFT,
                    top + SEARCH_TOP,
                    menuWidth - 16,
                    12,
                    this.search,
                    CustomEditBox.SEARCH_MESSAGE
            ));
            this.search.setHint(CustomEditBox.SEARCH_MESSAGE);
            this.search.setResponder(this::filter);
        }
        this.search.setTextColor(TextColours.getTextColour());
        this.search.setBordered(false);
        this.search.setValue(this.search.getValue());
        this.search.setTabOrderGroup(-1);
        ifSearchables(() -> this.addRenderableWidget(SearchablesUtil.getWrappedAutocomplete(this.search)));
        if (!preservedQuery.isEmpty() && this.search != null) this.search.setValue(preservedQuery);
        preservedQuery = "";

        if (shouldFocusSearch)
            this.setInitialFocus(search);

        // close
        this.addRenderableWidget(GuiUtil.close(
                this.left + this.menuWidth - (3 + BUTTON_SIZE),
                this.top + GuiConstants.SMALL_MARGIN,
                button -> this.onClose()));

        // mod settings
        this.addRenderableWidget(new ImageButton(
                        this.left + this.menuWidth - 2 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        GuiUtil.twoSprite("mod_settings/button"),
                        button -> Minecraft.getInstance().setScreen(ChestTrackerConfigScreenBuilder.build(this))))
                .setTooltip(Tooltip.create(translatable("chesttracker.gui.modSettings")));

        // change memory bank
        this.addRenderableWidget(new ImageButton(
                        this.left + this.menuWidth - 3 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        GuiUtil.twoSprite("change_memory_bank/button"),
                        this::openMemoryManager))
                .setTooltip(Tooltip.create(translatable("chesttracker.gui.openMemoryManager")));

        // memory bank settings
        this.addRenderableWidget(new ImageButton(
                        this.left + this.menuWidth - 4 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        GuiUtil.twoSprite("memory_bank_settings/button"),
                        this::openMemoryBankSettings))
                .setTooltip(Tooltip.create(translatable("chesttracker.gui.memoryBankSettings")));

        // filtering
        this.addRenderableWidget(new ChangeableImageButton(
                        this.left + this.menuWidth - 5 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        containerFilter.sprites,
                        CommonComponents.EMPTY,
                        this::cycleContainerFilter))
                .setTooltip(this.getContainerFilterTooltip());

        // item sort
        this.addRenderableWidget(new ChangeableImageButton(
                        this.left + this.menuWidth - 6 * (3 + BUTTON_SIZE),
                        this.top + GuiConstants.SMALL_MARGIN,
                        BUTTON_SIZE,
                        BUTTON_SIZE,
                        itemSort.sprites,
                        CommonComponents.EMPTY,
                        this::cycleItemSort))
                .setTooltip(this.getItemSortTooltip());

        // resize
        if (config.gui.showResizeWidget)
            this.resize = this.addRenderableWidget(new ResizeWidget(left + menuWidth - 10, top + menuHeight - 10, left, top,
                    GuiConstants.GRID_SLOT_SIZE, config.gui.gridWidth, config.gui.gridHeight,
                    GuiConstants.MIN_GRID_COLUMNS, GuiConstants.MIN_GRID_ROWS, GuiConstants.MAX_GRID_WIDTH, GuiConstants.MAX_GRID_HEIGHT, (w, h) -> {
                ChestTracker.LOGGER.debug("Resizing to {}w, {}h", w, h);
                ChestTrackerConfig.INSTANCE.instance().gui.gridWidth = w;
                ChestTrackerConfig.INSTANCE.instance().gui.gridHeight = h;
                ChestTrackerConfig.INSTANCE.save();
                rebuildWidgets();
            }));

        // key buttons
        // fix bad order on first open of screen, kind of hacky
        // (ender chest profiles resolve their own icon; don't pollute saved icon order)
        bank.getKeys().stream()
                .filter(loc -> !EnderChestKeys.isProfileKey(loc))
                .forEach(loc -> bank.getMetadata().getVisualSettings().getOrCreateIcon(loc));

        var todo = bank.getKeys().stream()
                .filter(loc -> !EnderChestKeys.isProfileKey(loc))
                .sorted(Misc.bringToFront(bank.getMetadata()
                        .getVisualSettings()
                        .getKeyOrder())).toList();
        Map<Identifier, ItemButton> buttons = new HashMap<>();

        for (int index = 0; index < todo.size(); index++) {
            var resloc = todo.get(index);

            // get the relevant icon
            var icon = bank.getMetadata().getVisualSettings().getOrCreateIcon(resloc);
            var button = this.addRenderableWidget(new ItemButton(icon,
                    this.left - MEMORY_ICON_OFFSET,
                    this.top + index * MEMORY_ICON_SPACING, b -> {
                // unhighlight old
                if (buttons.containsKey(this.currentMemoryKey))
                    buttons.get(this.currentMemoryKey).setHighlighted(false);
                if (this.echestButton != null) this.echestButton.setHighlighted(false);
                // leaving the dropdown: close it
                this.dropdownOpen = false;
                this.profileRows.forEach(p -> {
                    p.visible = false;
                    p.setHighlighted(false);
                });

                // set item list
                this.currentMemoryKey = resloc;
                updateItems();

                // highlight new
                buttons.get(resloc).setHighlighted(true);
            }, ItemButton.Background.CUSTOM));

            button.setTooltip(Tooltip.create(Component.literal(resloc.toString())));

            buttons.put(resloc, button);

            // inital button highlight
            if (currentMemoryKey.equals(resloc)) button.setHighlighted(true);
        }

        // ender chest profiles: single shared icon, dropdown rows of player heads below it
        this.profileRows.clear();
        this.profiles.clear();
        this.echestButton = null;
        var echestKeys = bank.getKeys().stream()
                .filter(EnderChestKeys::isProfileKey)
                .sorted(Comparator.comparing(Identifier::toString)).toList();
        if (!echestKeys.isEmpty()) {
            collectProfiles(bank);
            int echestIndex = todo.size();
            this.echestButton = this.addRenderableWidget(new ItemButton(
                    Items.ENDER_CHEST.getDefaultInstance(),
                    this.left - MEMORY_ICON_OFFSET,
                    this.top + echestIndex * MEMORY_ICON_SPACING, b -> {
                this.dropdownOpen = !this.dropdownOpen;
                this.profileRows.forEach(p -> p.visible = this.dropdownOpen);
                if (this.dropdownOpen && !EnderChestKeys.isProfileKey(this.currentMemoryKey))
                    selectOwnProfile(buttons);
                else if (this.dropdownOpen) refreshRowHighlight();
            }, ItemButton.Background.CUSTOM));
            this.echestButton.setTooltip(Tooltip.create(
                    Component.literal("Ender Chests (" + profiles.size() + " players)")));
            for (int i = 0; i < profiles.size(); i++) {
                Profile profile = profiles.get(i);
                EnderProfileButton row = new EnderProfileButton(
                        EnderProfileButton.headFor(profile.uuid()),
                        this.left - MEMORY_ICON_OFFSET,
                        this.top + (echestIndex + 1 + i) * MEMORY_ICON_SPACING,
                        b -> selectProfile(profile, buttons));
                row.setTooltip(Tooltip.create(EnderProfileButton.nameTooltip(profile.name(), profile.uuid())));
                row.visible = this.dropdownOpen;
                this.addRenderableWidget(row);
                this.profileRows.add(row);
            }
            this.dropdownOpen = EnderChestKeys.isProfileKey(this.currentMemoryKey);
            this.profileRows.forEach(p -> p.visible = this.dropdownOpen);
            if (EnderChestKeys.isProfileKey(this.currentMemoryKey)) {
                this.echestButton.setHighlighted(true);
                refreshRowHighlight();
            }
        }

        updateItems();
    }

    @Override
    public void tick() {
        super.tick();
        // pulls can land new profiles while the screen is open — rebuild so they appear
        // without needing to close and reopen (query + selection survive, see below)
        var bankOpt = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (bankOpt.isEmpty()) return;
        Set<Identifier> keys = bankOpt.get().getKeys();
        if (this.lastSeenKeys.isEmpty()) {
            this.lastSeenKeys = new HashSet<>(keys);
            return;
        }
        if (!this.lastSeenKeys.equals(keys)) {
            preservedQuery = this.search != null ? this.search.getValue() : "";
            preservedKey = this.currentMemoryKey;
            restorePending = true;
            Minecraft.getInstance().setScreen(new ChestTrackerScreen(this.parent));
        }
    }

    private void collectProfiles(MemoryBankImpl bank) {
        var player = Minecraft.getInstance().player;
        UUID ownUuid = player != null ? player.getUUID() : null;
        List<Profile> others = new ArrayList<>();
        Profile own = null;
        for (Identifier key : bank.getKeys()) {
            if (!EnderChestKeys.isProfileKey(key)) continue;
            // legacy shared keys are hidden (migration absorbs them on load)
            if (EnderChestKeys.isHiddenLegacyKey(key)) continue;
            Optional<UUID> owner = EnderChestKeys.ownerUuid(key);
            if (owner.isEmpty()) continue;
            String name = CMSyncManager.INSTANCE.ownerName(bank.getId(), owner.get());
            if (name == null) name = "Player " + owner.get().toString().substring(0, 8);
            Profile profile = new Profile(key, owner.get(), name);
            if (owner.get().equals(ownUuid)) own = profile;
            else others.add(profile);
        }
        others.sort(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER));
        if (own != null) this.profiles.add(own);
        this.profiles.addAll(others);
    }

    private void selectProfile(Profile profile, Map<Identifier, ItemButton> buttons) {
        if (buttons.containsKey(this.currentMemoryKey))
            buttons.get(this.currentMemoryKey).setHighlighted(false);
        this.currentMemoryKey = profile.key();
        updateItems();
        if (this.echestButton != null) this.echestButton.setHighlighted(true);
        refreshRowHighlight();
    }

    private void selectOwnProfile(Map<Identifier, ItemButton> buttons) {
        var player = Minecraft.getInstance().player;
        UUID ownUuid = player != null ? player.getUUID() : null;
        for (Profile profile : this.profiles) {
            if (ownUuid != null && ownUuid.equals(profile.uuid())) {
                selectProfile(profile, buttons);
                return;
            }
        }
        if (!this.profiles.isEmpty()) selectProfile(this.profiles.get(0), buttons);
    }

    private void refreshRowHighlight() {
        for (int i = 0; i < this.profiles.size() && i < this.profileRows.size(); i++)
            this.profileRows.get(i).setHighlighted(this.profiles.get(i).key().equals(this.currentMemoryKey));
    }

    private void cycleItemSort(ChangeableImageButton button) {
        itemSort = Misc.next(itemSort);
        button.setTooltip(getItemSortTooltip());
        button.setSprites(itemSort.sprites);
        updateItems();
    }

    private Tooltip getItemSortTooltip() {
        return Tooltip.create(translatable("chesttracker.gui.itemSort", itemSort.tooltip));
    }

    private Tooltip getContainerFilterTooltip() {
        return Tooltip.create(translatable("chesttracker.gui.containerFilter", containerFilter.tooltip));
    }

    private void cycleContainerFilter(ChangeableImageButton button) {
        containerFilter = Misc.next(containerFilter);
        button.setTooltip(getContainerFilterTooltip());
        button.setSprites(containerFilter.sprites);
        updateItems();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
    }

    /**
     * Update the cached item list from the current Memory Bank, then runs a filter operation.
     */
    private void updateItems() {
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            int maxRange = bank.getMetadata().getSearchSettings().itemListRange;

            CountingPredicate predicate = getItemListFilter(maxRange);

            this.items = bank.getCounts(currentMemoryKey, predicate, bank.getMetadata().getSearchSettings().stackMergeMode, bank.getMetadata().getSearchSettings().unpackNested)
                    .stream()
                    .sorted(itemSort.sort)
                    .toList();

            filter(this.search.getValue());
        });
    }

    private CountingPredicate getItemListFilter(int maxRange) {
        CountingPredicate predicate = containerFilter.filter;

        // apply max range if necessary
        if (Minecraft.getInstance().player != null && Objects.equals(ProviderUtils.getPlayersCurrentKey().orElse(null), currentMemoryKey)) {
            Vec3 origin = Minecraft.getInstance().player.getEyePosition();
            predicate = predicate.and(CountingPredicate.within(origin, maxRange));
        }
        return predicate;
    }

    /**
     * Update the items list with the currently cached items, filtered by the search bar.
     */
    private void filter(String filter) {
        List<ItemStack> filtered;
        if (Compatibility.SEARCHABLES) {
            filtered = SearchablesUtil.ITEM_STACK.filterEntries(this.items, filter.toLowerCase());
        } else {
            filtered = this.items.stream().filter(stack -> ItemStacks.defaultPredicate(stack, filter.toLowerCase()))
                    .toList();
        }
        this.itemList.setItems(filtered);
        var guiConfig = ChestTrackerConfig.INSTANCE.instance().gui;
        this.scroll.setDisabled(filtered.size() <= (guiConfig.gridWidth * guiConfig.gridHeight));
    }

    /**
     * Test for autocomplete-specific operations. Used to check mouse operations over the autocomplete bar.
     */
    private boolean ifSearchables(Predicate<AbstractWidget> predicate) {
        return Compatibility.SEARCHABLES && SearchablesUtil.ifSearchables(this.search, predicate);
    }

    private void ifSearchables(Runnable ifSearchables) {
        ifSearchables(ignored -> {
            ifSearchables.run();
            return false;
        });
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
        this.itemList.setHideTooltip(this.search.isFocused() && ifSearchables(a -> a.isMouseOver(mouseX, mouseY)));
        super.extractRenderState(graphics, mouseX, mouseY, tickDelta); // widgets
        graphics.text(this.font, this.title, left + TITLE_LEFT, top + TITLE_TOP, TextColours.getLabelColour(), false); // title
        this.itemList.renderTooltip(graphics);
    }

    @Override
    public void extractBackground(@NotNull GuiGraphicsExtractor graphics, int i, int j, float f) {
        super.extractBackground(graphics, i, j, f);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiUtil.BACKGROUND_SPRITE, left, top, menuWidth, menuHeight);
        ifSearchables(() -> graphics.blitSprite(RenderPipelines.GUI_TEXTURED, GuiUtil.SEARCH_BAR_SPRITE, search.getX() - 2, search.getY() - 2, search.getWidth() + 4, search.getHeight()));
    }

    @Override
    public boolean charTyped(@NotNull CharacterEvent event) {
        if (ignoreTextInput) {
            return false;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(@NotNull KeyEvent event) {
        this.ignoreTextInput = false;
        boolean searchFocused = this.search != null && this.search.isFocused();
        if (!searchFocused && red.jackf.chesttracker.impl.ChestTracker.OPEN_GUI.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(@NotNull MouseButtonEvent event, boolean isDoubleClick) {
        if (resize != null && resize.isMouseOver(event.x(), event.y())) {
            boolean handled = resize.mouseClicked(event, isDoubleClick);
            if (handled) return true;
        }
        if (this.search.isFocused() && ifSearchables(a -> a.mouseClicked(event, isDoubleClick)))
            return true;

        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseReleased(@NotNull MouseButtonEvent event) {
        if (scroll != null && (scroll.isScrolling() || scroll.isMouseOver(event.x(), event.y()))) {
            if (scroll.mouseReleased(event)) return true;
        }
        if (resize != null) {
            boolean handled = resize.mouseReleased(event);
            if (handled) return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(@NotNull MouseButtonEvent event, double mouseX, double mouseY) {
        if (scroll != null && (scroll.isScrolling() || scroll.isMouseOver(mouseX, mouseY))) {
            if (scroll.mouseDragged(event, mouseX, mouseY)) {
                return true;
            }
        }
        if (resize != null && resize.mouseDragged(event, mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(event, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Searchables Edit Box Support
        double finalDelta = deltaY;
        if (search.isFocused() && ifSearchables(a -> a.mouseScrolled(mouseX, mouseY, deltaX, finalDelta))) {
            return true;
        } else if (itemList.isMouseOver(mouseX, mouseY) || scroll.isMouseOver(mouseX, mouseY)) {
            deltaY /= Math.max(1, itemList.getRows() - ChestTrackerConfig.INSTANCE.instance().gui.gridHeight);
            return scroll.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }


    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void openMemoryManager(Button ignored) {
        Minecraft.getInstance().setScreen(new MemoryBankManagerScreen(
                () -> MemoryBankAccessImpl.INSTANCE.getLoadedInternal().isEmpty() ? parent : this,
                // return to this screen unless the memories have been unloaded, in which case go to the parent
                () -> Minecraft.getInstance().setScreen(this)
        ));
    }

    private void openMemoryBankSettings(Button button) {
        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            Minecraft.getInstance().setScreen(new EditMemoryBankScreen(
                    this,
                    this::updateItems,
                    bank.getId()
            ));
        });
    }

    public enum ContainerFilter {
        ALL(GuiUtil.twoSprite("container_filter/all"),
                translatable("chesttracker.gui.containerFilter.all"),
                (pos, memory) -> true),
        CHESTS(GuiUtil.twoSprite("container_filter/chests"),
                translatable("chesttracker.gui.containerFilter.chests"),
                                (pos, memory) -> memory.container().isPresent() || !memory.items().isEmpty()),
        BARRELS(GuiUtil.twoSprite("container_filter/barrels"),
                translatable("chesttracker.gui.containerFilter.barrels"),
                (pos, memory) -> memory.container().map(b -> b instanceof BarrelBlock).orElse(false)),
        SHULKER_BOXES(GuiUtil.twoSprite("container_filter/shulker_boxes"),
                translatable("chesttracker.gui.containerFilter.shulkerBoxes"),
                (pos, memory) -> memory.container().map(b -> b instanceof ShulkerBoxBlock).orElse(false)),
        HOPPERS(GuiUtil.twoSprite("container_filter/hoppers"),
                translatable("chesttracker.gui.containerFilter.hoppers"),
                (pos, memory) -> memory.container().map(b -> b instanceof HopperBlock).orElse(false)),
        FURNACES(GuiUtil.twoSprite("container_filter/furnaces"),
                translatable("chesttracker.gui.containerFilter.furnaces"),
                (pos, memory) -> memory.container().map(b -> b instanceof AbstractFurnaceBlock).orElse(false));

        private final WidgetSprites sprites;
        private final Component tooltip;
        private final CountingPredicate filter;

        ContainerFilter(WidgetSprites sprites, Component tooltip, CountingPredicate filter) {
            this.sprites = sprites;
            this.tooltip = tooltip;
            this.filter = filter;
        }
    }

    public enum ItemSort {
        COUNT_DESCENDING(GuiUtil.twoSprite("item_sort/count_descending"),
                translatable("chesttracker.gui.itemSort.countDescending"),
                Comparator.comparingInt(ItemStack::getCount).reversed()),
        COUNT_ASCENDING(GuiUtil.twoSprite("item_sort/count_ascending"),
                translatable("chesttracker.gui.itemSort.countAscending"),
                Comparator.comparingInt(ItemStack::getCount)),
        ALPHABETICAL_DESCENDING(GuiUtil.twoSprite("item_sort/alphabetical_descending"),
                translatable("chesttracker.gui.itemSort.alphabeticalDescending"),
                Comparator.comparing(stack -> stack.getDisplayName().getString().toLowerCase(Locale.ROOT))),
        ALPHABETICAL_ASCENDING(GuiUtil.twoSprite("item_sort/alphabetical_ascending"),
                translatable("chesttracker.gui.itemSort.alphabeticalAscending"),
                Comparator.<ItemStack, String>comparing(stack -> stack.getDisplayName().getString()
                        .toLowerCase(Locale.ROOT)).reversed());

        private final WidgetSprites sprites;
        private final Component tooltip;
        private final Comparator<ItemStack> sort;

        ItemSort(WidgetSprites sprites, Component tooltip, Comparator<ItemStack> sort) {
            this.sprites = sprites;
            this.tooltip = tooltip;
            this.sort = sort;
        }
    }
}
