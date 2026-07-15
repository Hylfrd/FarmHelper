package dev.hylfrd.farmhelper.client.control.inventory;

import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.inventory.InventoryCancelReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryClick;
import dev.hylfrd.farmhelper.control.inventory.InventoryClickGuard;
import dev.hylfrd.farmhelper.control.inventory.InventoryExecutionResult;
import dev.hylfrd.farmhelper.control.inventory.InventoryGuardStore;
import dev.hylfrd.farmhelper.control.inventory.InventoryItem;
import dev.hylfrd.farmhelper.control.inventory.InventoryPort;
import dev.hylfrd.farmhelper.control.inventory.InventoryScreenSnapshot;
import dev.hylfrd.farmhelper.control.inventory.InventorySlot;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperationToken;
import dev.hylfrd.farmhelper.control.inventory.ItemComponentSummary;
import dev.hylfrd.farmhelper.control.inventory.ScreenIdentity;
import dev.hylfrd.farmhelper.control.inventory.ScreenRevision;
import dev.hylfrd.farmhelper.runtime.snapshot.ItemSummary;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft 26.1.2 inventory adapter, owned by the client composition root.
 *
 * <p>{@link #executeGuardedClick(InventoryClickGuard)} performs observation, identity/revision,
 * slot/item/count/component/cursor eligibility checks, and {@code handleContainerInput} in one
 * client-thread call. This is the only inventory click write point.</p>
 */
public final class MinecraftInventoryPort implements InventoryPort {
    private final Minecraft client;
    private Object lastScreen;
    private AbstractContainerMenu lastMenu;
    private long nextEpoch = 1L;
    private long epoch;
    private long localContentRevision;
    private final InventoryGuardStore<ItemStack> itemGuards =
            new InventoryGuardStore<>(ItemStack::copy);
    private final InventoryGuardStore<ItemStack> cursorGuards =
            new InventoryGuardStore<>(ItemStack::copy);
    private RawContentFingerprint previousContent;

    public MinecraftInventoryPort() {
        this(Minecraft.getInstance());
    }

    public MinecraftInventoryPort(Minecraft client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Observation<InventoryScreenSnapshot> observe(
            InventoryOperationToken token, ControlOwner owner) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        return capture(true, token, owner).map(Captured::snapshot);
    }

    @Override
    public InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard) {
        Objects.requireNonNull(guard, "guard");
        Optional<ItemStack> expectedGuard = itemGuards.claim(
                guard.target().screen(),
                guard.target().revision(),
                guard.target().menuSlot(),
                guard.token(),
                guard.owner());
        if (expectedGuard.isEmpty()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.ITEM_CHANGED);
        }
        Optional<ItemStack> expectedCursorGuard = cursorGuards.claim(
                guard.target().screen(),
                guard.target().revision(),
                0,
                guard.token(),
                guard.owner());
        if (expectedCursorGuard.isEmpty()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.CURSOR_CHANGED);
        }
        Observation<Captured> observation = capture(false, null, null);
        if (!observation.isPresent()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.SCREEN_CLOSED);
        }
        Captured captured = observation.get();
        InventoryScreenSnapshot current = captured.snapshot();
        if (!current.identity().equals(guard.target().screen())) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.SCREEN_CHANGED);
        }
        Optional<InventorySlot> currentSlot = current.slot(guard.target().menuSlot());
        if (currentSlot.isEmpty()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.SLOT_OUT_OF_BOUNDS);
        }
        InventorySlot slot = currentSlot.orElseThrow();
        if (!slot.item().isPresent()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.ITEM_CHANGED);
        }
        ItemStack currentStack = captured.menu().getSlot(slot.menuSlot()).getItem();
        ItemStack expectedStack = expectedGuard.orElseThrow();
        if (!ItemStack.isSameItemSameComponents(expectedStack, currentStack)) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.ITEM_CHANGED);
        }
        if (currentStack.getCount() != expectedStack.getCount()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.COUNT_CHANGED);
        }
        if (!slot.hotbarSelection().equals(guard.hotbarSelection())) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.SLOT_CHANGED);
        }
        if (!current.cursor().equals(guard.cursor())) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.CURSOR_CHANGED);
        }
        ItemStack currentCursor = captured.menu().getCarried();
        ItemStack expectedCursor = expectedCursorGuard.orElseThrow();
        if (!ItemStack.isSameItemSameComponents(expectedCursor, currentCursor)
                || expectedCursor.getCount() != currentCursor.getCount()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.CURSOR_CHANGED);
        }
        if (!current.revision().equals(guard.target().revision())) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.REVISION_CHANGED);
        }
        if (!slot.active() || slot.active() != guard.slotActive()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.SLOT_INACTIVE);
        }
        if (!slot.mayPickup() || slot.mayPickup() != guard.mayPickup()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.PICKUP_DENIED);
        }

        ClickEncoding encoding = encode(guard.click());
        if (!guard.authority().isActive()) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.STALE_TOKEN);
        }
        client.gameMode.handleContainerInput(
                captured.menu().containerId,
                slot.menuSlot(),
                encoding.button(),
                encoding.input(),
                captured.player());
        return InventoryExecutionResult.success();
    }

    @Override
    public void releaseOperation(InventoryOperationToken token, ControlOwner owner) {
        itemGuards.clearOperation(token, owner);
        cursorGuards.clearOperation(token, owner);
    }

    @Override
    public Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected) {
        Objects.requireNonNull(expected, "expected");
        Observation<Captured> observation = capture(false, null, null);
        if (!observation.isPresent()) {
            return Optional.of(InventoryCancelReason.SCREEN_CLOSED);
        }
        Captured captured = observation.get();
        if (!captured.snapshot().identity().equals(expected)) {
            return Optional.of(InventoryCancelReason.SCREEN_CHANGED);
        }
        captured.screen().onClose();
        return Optional.empty();
    }

    private Observation<Captured> capture(
            boolean rememberItemGuards,
            InventoryOperationToken token,
            ControlOwner owner) {
        requireClientThread();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || client.player == null
                || client.gameMode == null) {
            clearScreenLifetime();
            return Observation.absent();
        }
        AbstractContainerMenu menu = screen.getMenu();
        LocalPlayer player = client.player;
        if (player.containerMenu != menu) {
            clearScreenLifetime();
            return Observation.absent();
        }
        if (screen != lastScreen || menu != lastMenu) {
            beginScreenLifetime(screen, menu);
        }

        String type = menuType(menu, screen);
        ScreenIdentity identity = new ScreenIdentity(epoch, menu.containerId, type);
        List<InventorySlot> slots = new ArrayList<>(menu.slots.size());
        Map<Integer, ItemStack> observedItemGuards = new LinkedHashMap<>();
        List<ItemStack> rawSlots = new ArrayList<>(menu.slots.size());
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            ItemStack stack = slot.getItem();
            rawSlots.add(stack);
            if (!stack.isEmpty()) {
                observedItemGuards.put(index, stack);
            }
            slots.add(new InventorySlot(
                    index,
                    summarize(stack),
                    slot.isActive(),
                    slot.mayPickup(player),
                    hotbarSlot(slot, player)));
        }
        Observation<InventoryItem> cursor = summarize(menu.getCarried());
        Observation<HotbarSelection> selected = Observation.present(
                new HotbarSelection(player.getInventory().getSelectedSlot()));
        ScreenSnapshot screenSummary = new ScreenSnapshot(
                epoch,
                Observation.present(type),
                boundedScreenTitle(screen.getTitle()));
        RawContentFingerprint content = new RawContentFingerprint(
                screenSummary, slots, rawSlots, menu.getCarried(), cursor, selected);
        if (previousContent != null && !previousContent.sameAs(content)) {
            if (localContentRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("inventory local content revision exhausted");
            }
            localContentRevision++;
        }
        previousContent = content;
        ScreenRevision revision = new ScreenRevision(menu.getStateId(), localContentRevision);
        InventoryScreenSnapshot snapshot = new InventoryScreenSnapshot(
                identity,
                revision,
                screenSummary,
                slots,
                cursor,
                selected);
        if (rememberItemGuards) {
            itemGuards.replaceObservation(identity, revision, token, owner, observedItemGuards);
            cursorGuards.replaceObservation(
                    identity, revision, token, owner, Map.of(0, menu.getCarried()));
        } else {
            itemGuards.retainOnly(identity, revision);
            cursorGuards.retainOnly(identity, revision);
        }
        return Observation.present(new Captured(snapshot, screen, menu, player));
    }

    private void requireClientThread() {
        if (!client.isSameThread()) {
            throw new IllegalStateException("inventory adapter must run on the Minecraft client thread");
        }
    }

    private void beginScreenLifetime(Object screen, AbstractContainerMenu menu) {
        if (nextEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("inventory screen epoch exhausted");
        }
        epoch = nextEpoch++;
        lastScreen = screen;
        lastMenu = menu;
        localContentRevision = 0L;
        previousContent = null;
        itemGuards.clear();
        cursorGuards.clear();
    }

    private void clearScreenLifetime() {
        lastScreen = null;
        lastMenu = null;
        previousContent = null;
        localContentRevision = 0L;
        itemGuards.clear();
        cursorGuards.clear();
    }

    private static String menuType(AbstractContainerMenu menu, AbstractContainerScreen<?> screen) {
        Identifier key = menu.getType() == null ? null : BuiltInRegistries.MENU.getKey(menu.getType());
        return key == null ? screen.getClass().getName() : key.toString();
    }

    private static Optional<HotbarSelection> hotbarSlot(Slot slot, LocalPlayer player) {
        int containerSlot = slot.getContainerSlot();
        return slot.container == player.getInventory() && containerSlot >= 0
                && containerSlot < HotbarSelection.SLOT_COUNT
                ? Optional.of(new HotbarSelection(containerSlot))
                : Optional.empty();
    }

    private static Observation<InventoryItem> summarize(ItemStack stack) {
        if (stack.isEmpty()) {
            return Observation.absent();
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            throw new IllegalStateException("item has no registry identifier");
        }
        ItemComponentSummary components = new ItemComponentSummary(
                componentText(stack.get(DataComponents.CUSTOM_NAME)),
                componentText(stack.get(DataComponents.ITEM_NAME)),
                lore(stack.get(DataComponents.LORE)));
        return Observation.present(new InventoryItem(
                new ItemSummary(ResourceIdentifier.parse(key.toString()), stack.getCount()),
                components));
    }

    private static Optional<String> componentText(Component component) {
        if (component == null) {
            return Optional.empty();
        }
        int characterLimit = ItemComponentSummary.MAX_NAME_CODE_POINTS * 2;
        return Optional.of(ItemComponentSummary.sanitize(
                component.getString(characterLimit), ItemComponentSummary.MAX_NAME_CODE_POINTS));
    }

    private static List<String> lore(ItemLore lore) {
        if (lore == null) {
            return List.of();
        }
        int characterLimit = ItemComponentSummary.MAX_LORE_CODE_POINTS * 2;
        return lore.lines().stream()
                .limit(ItemComponentSummary.MAX_LORE_LINES)
                .map(component -> component.getString(characterLimit))
                .toList();
    }

    private static Observation<String> boundedScreenTitle(Component component) {
        if (component == null) {
            return Observation.unknown();
        }
        String value = component.getString(GameTextInputBudget.MAX_LINE_CHARACTERS + 1);
        return value.length() > GameTextInputBudget.MAX_LINE_CHARACTERS
                ? Observation.unknown()
                : Observation.present(value);
    }

    private static ClickEncoding encode(InventoryClick click) {
        return switch (click) {
            case InventoryClick.Pickup pickup -> new ClickEncoding(
                    pickup.button() == InventoryClick.PickupButton.PRIMARY ? 0 : 1,
                    ContainerInput.PICKUP);
            case InventoryClick.QuickMove ignored -> new ClickEncoding(0, ContainerInput.QUICK_MOVE);
            case InventoryClick.SwapWithHotbar swap ->
                    new ClickEncoding(swap.hotbar().slot(), ContainerInput.SWAP);
        };
    }

    private record ClickEncoding(int button, ContainerInput input) { }

    private record Captured(
            InventoryScreenSnapshot snapshot,
            AbstractContainerScreen<?> screen,
            AbstractContainerMenu menu,
            LocalPlayer player) { }

    /** Client-private full component fingerprint; inherited Object.toString cannot expose stacks. */
    private static final class RawContentFingerprint {
        private final ScreenSnapshot screen;
        private final List<InventorySlot> slots;
        private final List<ItemStack> rawSlots;
        private final ItemStack rawCursor;
        private final Observation<InventoryItem> cursor;
        private final Observation<HotbarSelection> selectedHotbar;

        private RawContentFingerprint(
                ScreenSnapshot screen,
                List<InventorySlot> slots,
                List<ItemStack> rawSlots,
                ItemStack rawCursor,
                Observation<InventoryItem> cursor,
                Observation<HotbarSelection> selectedHotbar) {
            this.screen = screen;
            this.slots = List.copyOf(slots);
            this.rawSlots = rawSlots.stream().map(ItemStack::copy).toList();
            this.rawCursor = rawCursor.copy();
            this.cursor = cursor;
            this.selectedHotbar = selectedHotbar;
        }

        private boolean sameAs(RawContentFingerprint other) {
            if (!screen.equals(other.screen)
                    || !slots.equals(other.slots)
                    || !cursor.equals(other.cursor)
                    || !selectedHotbar.equals(other.selectedHotbar)
                    || rawSlots.size() != other.rawSlots.size()
                    || !sameStack(rawCursor, other.rawCursor)) {
                return false;
            }
            for (int index = 0; index < rawSlots.size(); index++) {
                if (!sameStack(rawSlots.get(index), other.rawSlots.get(index))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean sameStack(ItemStack first, ItemStack second) {
            return first.getCount() == second.getCount()
                    && ItemStack.isSameItemSameComponents(first, second);
        }
    }
}
