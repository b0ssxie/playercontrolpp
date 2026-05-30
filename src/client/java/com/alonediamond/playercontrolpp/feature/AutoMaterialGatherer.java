package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.text.Text;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.*;

public class AutoMaterialGatherer {
    private static final AutoMaterialGatherer INSTANCE = new AutoMaterialGatherer();

    public enum State {
        IDLE, ANALYZING, SEARCHING, PATHING, OPENING_CONTAINER,
        TRANSFERRING_ITEM, VERIFYING, NEXT_ITEM, COMPLETED, FAILED, STOPPED
    }

    private State state = State.IDLE;
    private boolean active;
    private MinecraftClient client;

    // Material list data
    private List<MaterialItemEntry> missingItems = new ArrayList<>();
    private int currentItemIndex;
    private Item currentTargetItem;
    private int targetNeededTotal;
    private int currentlyGathered;

    // Chest search data
    private List<BlockPos> foundPositions = new ArrayList<>();
    private int currentPosIndex;
    private int chestRetryCount;

    // Baritone pathing tracking
    private Vec3d lastPlayerPos = Vec3d.ZERO;
    private int stuckTicks;
    private int pathingTicks;
    private boolean pathingWasActive;
    private BlockPos currentPathTarget;

    // Container interaction
    private int transferCooldown;
    private boolean containerJustOpened;
    private int openAttemptCount;
    private BlockPos currentContainerTarget;
    private List<BlockPos> adjacentContainerTargets;
    private int adjacentTryIndex;
    private ItemTransferStrategy.TransferPlan currentTransferPlan = ItemTransferStrategy.TransferPlan.NONE;
    private Map<Item, Integer> stacksTakenThisContainer = new HashMap<>();
    private Map<Item, Integer> shulkerBoxesTakenThisContainer = new HashMap<>();

    private AutoMaterialGatherer() {}

    public static AutoMaterialGatherer getInstance() { return INSTANCE; }

    public State getState() { return state; }
    public boolean isActive() { return active; }

    public boolean toggle() {
        if (active) {
            stop();
            return false;
        } else {
            return start();
        }
    }

    private boolean start() {
        client = MinecraftClient.getInstance();
        if (client.player == null) return false;

        if (!areAllThreeModsPresent()) {
            MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.mods_missing");
            return false;
        }

        // Full reset: cancel any stray Baritone processes from previous runs
        cancelBaritonePathing();
        closeAnyContainer();

        active = true;
        state = State.IDLE;
        chestRetryCount = 0;
        stuckTicks = 0;
        pathingTicks = 0;
        pathingWasActive = false;
        currentPathTarget = null;
        missingItems.clear();
        foundPositions.clear();
        currentItemIndex = 0;
        currentPosIndex = 0;
        currentTargetItem = null;
        currentlyGathered = 0;
        targetNeededTotal = 0;
        transferCooldown = 0;
        containerJustOpened = false;
        openAttemptCount = 0;
        currentContainerTarget = null;
        adjacentContainerTargets = null;
        adjacentTryIndex = 0;
        currentTransferPlan = ItemTransferStrategy.TransferPlan.NONE;
        stacksTakenThisContainer.clear();
        shulkerBoxesTakenThisContainer.clear();

        MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.started");
        return true;
    }

    public void stop() {
        cancelBaritonePathing();
        closeAnyContainer();
        active = false;
        state = State.STOPPED;
        MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.stopped");
    }

    private void setState(State newState) {
        this.state = newState;
        switch (newState) {
            case ANALYZING:
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.analyzing");
                break;
            case SEARCHING:
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.searching");
                break;
            case PATHING:
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.pathing");
                break;
            case OPENING_CONTAINER:
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.opening");
                break;
            case TRANSFERRING_ITEM:
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.transferring");
                break;
            case VERIFYING:
                break;
            case NEXT_ITEM:
                break;
            case COMPLETED:
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.completed");
                active = false;
                cancelBaritonePathing();
                break;
            case FAILED:
                // Continue to next item instead of stopping the entire process
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.pathing_stuck");
                cancelBaritonePathing();
                closeAnyContainer();
                adjacentContainerTargets = null;
                adjacentTryIndex = 0;
                break;
            case STOPPED:
                active = false;
                cancelBaritonePathing();
                closeAnyContainer();
                break;
            default:
                break;
        }
    }

    public void tick(MinecraftClient mc) {
        this.client = mc;
        if (!active || mc.player == null || mc.player.isDead()) {
            if (active) {
                stop();
            }
            return;
        }

        // Handle transfer cooldown and container opening retry
        if (transferCooldown > 0) {
            transferCooldown--;
            if (containerJustOpened && transferCooldown <= 0) {
                containerJustOpened = false;

                if (mc.currentScreen instanceof HandledScreen<?>) {
                    // Container opened — verify contents match target item
                    if (containerHasAnyMissingItem()) {
                        setState(State.TRANSFERRING_ITEM);
                        transferCooldown = 4;
                        openAttemptCount = 0;
                    } else {
                        // Empty or contains only non-missing items — skip without flicker
                        mc.player.closeHandledScreen();
                        transferCooldown = 8; // longer delay to avoid GUI flicker
                        retryAdjacentOrFail();
                    }
                } else {
                    // Container didn't open — retry with escalation
                    openAttemptCount++;
                    if (openAttemptCount < 3) {
                        // Retry: jump on odd attempts, normal on even
                        boolean jump = (openAttemptCount == 2);
                        openContainerWithRetry(currentContainerTarget, jump, openAttemptCount);
                    } else {
                        // 3 attempts failed at this position — try adjacent positions
                        retryAdjacentOrFail();
                    }
                }
            }
        }

        switch (state) {
            case IDLE:
                if (active) {
                    setState(State.ANALYZING);
                }
                break;

            case ANALYZING:
                doAnalyzeMaterials();
                break;

            case SEARCHING:
                doSearchContainer();
                break;

            case PATHING:
                doCheckPathingProgress(mc);
                break;

            case TRANSFERRING_ITEM:
                doTransferItems(mc);
                break;

            case VERIFYING:
                doVerifyQuantity();
                break;

            case NEXT_ITEM:
                doNextItem();
                break;

            case OPENING_CONTAINER:
                // Handled by transferCooldown
                break;

            case FAILED:
                // Skip current item and continue with next
                skipCurrentItem();
                break;

            case STOPPED:
                // Already stopped, nothing to do
                break;

            default:
                break;
        }
    }

    // ---- Phase implementations ----

    private void doAnalyzeMaterials() {
        try {
            Object materialList = getLitematicaMaterialList();
            if (materialList == null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.no_material_list");
                setState(State.STOPPED);
                return;
            }

            Object hudRenderer = materialList.getClass().getMethod("getHudRenderer").invoke(materialList);
            boolean hudShowing = (Boolean) hudRenderer.getClass()
                    .getMethod("getShouldRenderCustom").invoke(hudRenderer);
            if (!hudShowing) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.no_hud");
                setState(State.STOPPED);
                return;
            }

            if (isInventoryFull(client)) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.inventory_full");
                setState(State.STOPPED);
                return;
            }

            // Build the global ignore set from the config string (comma-separated item IDs)
            Set<String> globalIgnoreSet = buildGlobalIgnoreSet();

            // Get Litematica's own ignored entries (player-ignored in the material list GUI)
            Set<Object> litematicaIgnored = getLitematicaIgnoredSet(materialList);

            // Force-update available counts from actual player inventory
            Object allMaterials = materialList.getClass()
                    .getMethod("getMaterialsAll").invoke(materialList);
            Class<?> utilsClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListUtils");
            utilsClass.getMethod("updateAvailableCounts", java.util.List.class,
                            net.minecraft.entity.player.PlayerEntity.class)
                    .invoke(null, allMaterials, client.player);

            // Read from full list, filter manually to respect both ignore systems
            List<?> allList = (List<?>) allMaterials;
            missingItems.clear();
            for (Object entry : allList) {
                // Skip entries ignored by Litematica's own ignore system
                if (litematicaIgnored.contains(entry)) continue;

                ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();

                // Skip entries in the global ignore list (if enabled)
                if (globalIgnoreSet.contains(itemId)) continue;

                int countMissing = (Integer) entry.getClass().getMethod("getCountMissing").invoke(entry);
                int countAvailable = (Integer) entry.getClass().getMethod("getCountAvailable").invoke(entry);
                int needed = countMissing - countAvailable;
                if (needed > 0) {
                    missingItems.add(new MaterialItemEntry(stack.getItem(), needed,
                            stack.getMaxCount()));
                }
            }

            // Sort by needed count descending — gather items with largest deficit first
            missingItems.sort((a, b) -> Integer.compare(b.neededCount, a.neededCount));

            if (missingItems.isEmpty()) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.all_materials_ready");
                setState(State.COMPLETED);
                return;
            }

            currentItemIndex = 0;
            setState(State.NEXT_ITEM);

        } catch (Exception e) {
            String msg = StringUtils.translate("playercontrolpp.message.baritone.analyze_error", e.getMessage());
            client.player.sendMessage(Text.of(msg), true);
            setState(State.STOPPED);
        }
    }

    /**
     * Gets Litematica's internal ignored-entries set via reflection.
     */
    private Set<Object> getLitematicaIgnoredSet(Object materialList) {
        try {
            return (Set<Object>) materialList.getClass().getField("ignored").get(materialList);
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /**
     * Builds the global ignore set from the config string.
     * Format: comma-separated item identifiers, e.g. "minecraft:water_bucket,minecraft:dirt".
     */
    private Set<String> buildGlobalIgnoreSet() {
        Set<String> set = new HashSet<>();
        if (!Configs.BaritoneSettings.ENABLE_GLOBAL_IGNORE.getBooleanValue()) return set;
        List<String> strings = Configs.BaritoneSettings.GLOBAL_IGNORE_LIST.getStrings();
        for (String s : strings) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    private void doNextItem() {
        if (currentItemIndex >= missingItems.size()) {
            if (isInventoryFull(client)) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.completed_full");
            }
            setState(State.COMPLETED);
            return;
        }

        MaterialItemEntry entry = missingItems.get(currentItemIndex);
        currentTargetItem = entry.item;
        targetNeededTotal = entry.neededCount;
        currentlyGathered = countItemInInventory(currentTargetItem);
        currentPosIndex = 0;
        chestRetryCount = 0;
        foundPositions.clear();

        if (currentlyGathered >= targetNeededTotal) {
            currentItemIndex++;
            setState(State.NEXT_ITEM);
            return;
        }

        // Calculate how many items to take
        int stillNeeded = targetNeededTotal - currentlyGathered;
        currentTransferPlan = ItemTransferStrategy.calculate(stillNeeded, entry.maxStackSize);
        stacksTakenThisContainer.clear();
        shulkerBoxesTakenThisContainer.clear();

        setState(State.SEARCHING);
    }

    private void doSearchContainer() {
        try {
            // Check inventory full
            if (isInventoryFull(client)) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.inventory_full");
                setState(State.STOPPED);
                return;
            }

            // Get ChestTracker loaded memory bank
            Object memoryBank = getChestTrackerMemoryBank();
            if (memoryBank == null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.no_cache");
                setState(State.STOPPED);
                return;
            }

            // Get search settings
            int searchRange = getChestTrackerSearchRange(memoryBank);
            if (searchRange == Integer.MAX_VALUE) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.range_infinite");
                setState(State.STOPPED);
                return;
            }
            int listRange = getChestTrackerListRange(memoryBank);
            if (listRange == Integer.MAX_VALUE) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.range_infinite");
                setState(State.STOPPED);
                return;
            }
            int effectiveRange = Math.min(searchRange, listRange);

            // Get current dimension key
            Identifier currentDim = getCurrentDimensionKey();
            if (currentDim == null) {
                setState(State.STOPPED);
                return;
            }

            // Get MemoryKey for current dimension
            Optional<?> memKeyOpt = (Optional<?>) memoryBank.getClass()
                    .getMethod("getKey", Identifier.class)
                    .invoke(memoryBank, currentDim);
            if (memKeyOpt.isEmpty()) {
                // No memories for this dimension
                skipCurrentItem();
                return;
            }

            Object memoryKey = memKeyOpt.get();
            Map<?, ?> memories = (Map<?, ?>) memoryKey.getClass()
                    .getMethod("getMemories").invoke(memoryKey);

            // Search only for direct item matches — skip items hidden inside shulker boxes.
            // ChestTracker cache coordinates for nested shulker items are unreliable.
            foundPositions.clear();
            BlockPos playerPos = client.player.getBlockPos();
            long rangeSq = (long) effectiveRange * effectiveRange;

            for (Map.Entry<?, ?> memEntry : memories.entrySet()) {
                BlockPos pos = (BlockPos) memEntry.getKey();
                if (pos.getSquaredDistance(playerPos) > rangeSq) continue;

                Object memory = memEntry.getValue();
                List<?> items = (List<?>) memory.getClass().getMethod("items").invoke(memory);

                for (Object itemObj : items) {
                    ItemStack stack = (ItemStack) itemObj;
                    if (stack.isEmpty()) continue;
                    if (itemsMatch(stack, currentTargetItem)) {
                        foundPositions.add(pos);
                        break;
                    }
                }
            }

            // Sort by distance
            foundPositions.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(playerPos)));

            if (foundPositions.isEmpty()) {
                String itemName = Registries.ITEM.getId(currentTargetItem).toString();
                String msg = StringUtils.translate("playercontrolpp.message.baritone.item_missing", itemName);
                client.player.sendMessage(Text.of(msg), true);
                skipCurrentItem();
                return;
            }

            currentPosIndex = 0;
            chestRetryCount = 0;
            adjacentContainerTargets = null;
            adjacentTryIndex = 0;
            navigateToContainer(foundPositions.get(0));

        } catch (Exception e) {
            String msg = StringUtils.translate("playercontrolpp.message.baritone.search_error", e.getMessage());
            client.player.sendMessage(Text.of(msg), true);
            skipCurrentItem();
        }
    }

    private void doCheckPathingProgress(MinecraftClient mc) {
        if (mc.player == null) return;

        pathingTicks++;

        // Check if inventory full during pathing
        if (isInventoryFull(mc)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.inventory_full");
            setState(State.STOPPED);
            return;
        }

        // Track whether Baritone has started actively pathing
        if (!pathingWasActive) {
            if (isBaritonePathing()) {
                pathingWasActive = true;
                stuckTicks = 0;
                lastPlayerPos = mc.player.getPos();
            }
        }

        // Stuck detection: player not moved for 5 seconds (only after pathing has started)
        if (pathingWasActive && pathingTicks > 5) {
            Vec3d currentPos = mc.player.getPos();
            double moved = currentPos.squaredDistanceTo(lastPlayerPos);
            if (moved < 0.04) {
                stuckTicks++;
                if (stuckTicks >= 100) { // 5 seconds at 20 tps
                    setState(State.FAILED);
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPlayerPos = currentPos;
        }

        // Check if Baritone has reached destination.
        // Only after: pathing started, minimum ticks elapsed, and pathing has stopped.
        if (pathingWasActive && pathingTicks > 5 && !isBaritonePathing()) {
            // Pathing was active and now stopped → arrived or cancelled
            stuckTicks = 0;
            lastPlayerPos = Vec3d.ZERO;
            pathingTicks = 0;
            pathingWasActive = false;

            if (currentPosIndex < foundPositions.size()) {
                setState(State.OPENING_CONTAINER);
                openContainerAt(foundPositions.get(currentPosIndex));
            } else {
                skipCurrentItem();
            }
        }

        // Timeout: if pathing never started after 40 ticks (~2 seconds), fail
        if (!pathingWasActive && pathingTicks > 40) {
            setState(State.FAILED);
        }
    }

    private void doTransferItems(MinecraftClient mc) {
        if (transferCooldown > 0) return;

        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            setState(State.VERIFYING);
            return;
        }

        if (isInventoryFull(mc)) {
            mc.player.closeHandledScreen();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.inventory_full");
            setState(State.STOPPED);
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) {
            mc.player.closeHandledScreen();
            setState(State.VERIFYING);
            return;
        }

        List<Slot> slots = handler.slots;
        boolean transferred = false;

        // Phase 1: Transfer loose items (non-shulker) first, then shulker boxes.
        // This respects the "prefer loose items when quantity is small" preference.

        // === Phase 1a: Loose items ===
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || isShulkerBox(stack)) continue;

            MaterialItemEntry matchedEntry = findMatchingMissingItem(stack);
            if (matchedEntry == null) continue;

            if (tryTransferLoose(mc, handler, slot, matchedEntry)) {
                transferred = true;
                transferCooldown = 4;
                return;
            }
        }

        // === Phase 1b: Shulker boxes — only if verified to contain needed items ===
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !isShulkerBox(stack)) continue;

            // Verify shulker box contents before taking
            if (!shulkerBoxContainsAnyMissingItem(stack)) continue;

            MaterialItemEntry bestEntry = findBestMissingItemForShulker(stack);
            if (bestEntry == null) continue;

            if (tryTransferShulker(mc, handler, slot, bestEntry)) {
                transferred = true;
                transferCooldown = 4;
                return;
            }
        }

        // No matching items left → close and verify
        if (!transferred) {
            mc.player.closeHandledScreen();
            transferCooldown = 8;
            setState(State.VERIFYING);
        }
    }

    /**
     * Finds the first missing-item entry whose item matches the given stack.
     */
    private MaterialItemEntry findMatchingMissingItem(ItemStack stack) {
        for (MaterialItemEntry entry : missingItems) {
            if (itemsMatch(stack, entry.item)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Finds which missing item this specific shulker box is best used for.
     * Only returns an entry if the shulker box actually contains that item type
     * AND the needed quantity justifies a box (> 2 stacks).
     */
    private MaterialItemEntry findBestMissingItemForShulker(ItemStack shulkerBox) {
        MaterialItemEntry best = null;
        int bestNeeded = 0;
        for (MaterialItemEntry entry : missingItems) {
            int have = countItemInInventory(entry.item) + countItemInShulkerBoxes(entry.item);
            int needed = entry.neededCount - have;
            if (needed > 128 && shulkerBoxContainsItem(shulkerBox, entry.item)) {
                if (needed > bestNeeded) {
                    bestNeeded = needed;
                    best = entry;
                }
            }
        }
        return best;
    }

    /**
     * Checks whether a shulker box item contains a specific target item.
     */
    private boolean shulkerBoxContainsItem(ItemStack shulkerBox, Item targetItem) {
        ContainerComponent container = shulkerBox.getComponents().get(DataComponentTypes.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : container.iterateNonEmpty()) {
            if (itemsMatch(inner, targetItem)) return true;
        }
        return false;
    }

    private boolean tryTransferLoose(MinecraftClient mc, ScreenHandler handler, Slot slot, MaterialItemEntry entry) {
        int have = countItemInInventory(entry.item);
        int needed = entry.neededCount - have;
        if (needed <= 0) return false;

        int taken = stacksTakenThisContainer.getOrDefault(entry.item, 0);
        int stackSize = entry.maxStackSize > 0 ? entry.maxStackSize : 64;
        int maxStacks = (needed + stackSize - 1) / stackSize; // ceiling division

        if (taken >= maxStacks) return false; // enough taken

        try {
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                    SlotActionType.QUICK_MOVE, mc.player);
            stacksTakenThisContainer.put(entry.item, taken + 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryTransferShulker(MinecraftClient mc, ScreenHandler handler, Slot slot, MaterialItemEntry entry) {
        int have = countItemInInventory(entry.item) + countItemInShulkerBoxes(entry.item);
        int needed = entry.neededCount - have;
        if (needed <= 0) return false;

        int taken = shulkerBoxesTakenThisContainer.getOrDefault(entry.item, 0);
        // One shulker box ≈ 27 * stackSize items; take enough boxes to cover needed
        int stackSize = entry.maxStackSize > 0 ? entry.maxStackSize : 64;
        int shulkerCap = 27 * stackSize;
        int maxBoxes = (needed + shulkerCap - 1) / shulkerCap;

        if (taken >= maxBoxes) return false;

        try {
            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0,
                    SlotActionType.QUICK_MOVE, mc.player);
            shulkerBoxesTakenThisContainer.put(entry.item, taken + 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int getCurrentItemMaxStackSize() {
        if (currentTargetItem == null) return 64;
        return currentTargetItem.getMaxCount();
    }

    private void doVerifyQuantity() {
        currentlyGathered = countItemInInventory(currentTargetItem);
        currentlyGathered += countItemInShulkerBoxes(currentTargetItem);

        if (currentlyGathered >= targetNeededTotal) {
            currentItemIndex++;
            setState(State.NEXT_ITEM);
        } else {
            // Need more — reset counters for next container
            stacksTakenThisContainer.clear();
            shulkerBoxesTakenThisContainer.clear();

            currentPosIndex++;
            adjacentContainerTargets = null;
            adjacentTryIndex = 0;
            if (currentPosIndex >= foundPositions.size()) {
                setState(State.SEARCHING);
                stacksTakenThisContainer.clear();
                shulkerBoxesTakenThisContainer.clear();
            } else {
                navigateToContainer(foundPositions.get(currentPosIndex));
            }
        }
    }

    private void skipCurrentItem() {
        currentItemIndex++;
        setState(State.NEXT_ITEM);
    }

    // ---- Helper methods ----

    private boolean isPlayerNearPosition(BlockPos pos, double maxDist) {
        if (client.player == null) return false;
        return client.player.getBlockPos().getSquaredDistance(pos) <= maxDist * maxDist;
    }

    private boolean isInventoryFull(MinecraftClient mc) {
        if (mc.player == null) return true;
        // Check all inventory slots (main inventory 0-35)
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int countItemInInventory(Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (itemsMatch(stack, item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItemInShulkerBoxes(Item targetItem) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (isShulkerBox(stack)) {
                count += countItemInShulkerBox(stack, targetItem);
            }
        }
        return count;
    }

    private int countItemInShulkerBox(ItemStack shulkerBox, Item targetItem) {
        // Try to get NBT components and count items inside
        try {
            Class<?> componentMapClass = Class.forName("net.minecraft.component.ComponentMap");
            // Use DataComponentTypes.CONTAINER or BlockEntityData to get contents
            // This is complex - for now, count shulkerBox.getCount() * shulkerBox.getMaxCount()
            // as an approximation. In practice, the verify cycle will confirm.
            return 0; // Can't easily peek inside without opening
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isShulkerBox(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id.getPath().contains("shulker_box");
    }

    private boolean itemsMatch(ItemStack stack, Item targetItem) {
        if (stack.getItem() == targetItem) return true;
        // Fallback: compare by registry ID
        Identifier stackId = Registries.ITEM.getId(stack.getItem());
        Identifier targetId = Registries.ITEM.getId(targetItem);
        return stackId.equals(targetId);
    }

    // ---- Mod detection ----

    public static boolean areAllThreeModsPresent() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("baritone")
                && loader.isModLoaded("litematica")
                && loader.isModLoaded("chesttracker");
    }

    // ---- Litematica integration (reflection) ----

    private Object getLitematicaMaterialList() throws Exception {
        Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
        return dmClass.getMethod("getMaterialList").invoke(null);
    }

    // ---- ChestTracker integration (reflection) ----

    private Object getChestTrackerMemoryBank() throws Exception {
        Class<?> accessClass = Class.forName("red.jackf.chesttracker.api.memory.MemoryBankAccess");
        Object instance = accessClass.getField("INSTANCE").get(null);
        Optional<?> loaded = (Optional<?>) instance.getClass().getMethod("getLoaded").invoke(instance);
        return loaded.orElse(null);
    }

    private int getChestTrackerSearchRange(Object memoryBank) throws Exception {
        Object metadata = memoryBank.getClass().getMethod("getMetadata").invoke(memoryBank);
        Object searchSettings = metadata.getClass().getMethod("getSearchSettings").invoke(metadata);
        return searchSettings.getClass().getField("searchRange").getInt(searchSettings);
    }

    private int getChestTrackerListRange(Object memoryBank) throws Exception {
        Object metadata = memoryBank.getClass().getMethod("getMetadata").invoke(memoryBank);
        Object searchSettings = metadata.getClass().getMethod("getSearchSettings").invoke(metadata);
        return searchSettings.getClass().getField("itemListRange").getInt(searchSettings);
    }

    private Identifier getCurrentDimensionKey() throws Exception {
        Class<?> utilsClass = Class.forName("red.jackf.chesttracker.api.providers.ProviderUtils");
        Optional<?> key = (Optional<?>) utilsClass.getMethod("getPlayersCurrentKey").invoke(null);
        return (Identifier) key.orElse(null);
    }

    // ---- Baritone integration (reflection) ----

    private Object getBaritone() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiClass.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    private void startBaritonePathing(BlockPos target) {
        currentPathTarget = target;
        pathingTicks = 0;
        stuckTicks = 0;
        pathingWasActive = false;
        lastPlayerPos = client.player != null ? client.player.getPos() : Vec3d.ZERO;

        try {
            // Cancel any existing path first
            cancelBaritonePathing();

            Object baritone = getBaritone();

            // Use GoalGetToBlock — paths adjacent to the container block (same as Baritone's #goto)
            Object customGoalProcess = baritone.getClass()
                    .getMethod("getCustomGoalProcess").invoke(baritone);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
            Object goal = goalClass.getConstructor(BlockPos.class).newInstance(target);

            customGoalProcess.getClass()
                    .getMethod("setGoalAndPath",
                            Class.forName("baritone.api.pathing.goals.Goal"))
                    .invoke(customGoalProcess, goal);

        } catch (Exception e) {
            // Fallback: try command execution
            try {
                Object baritone = getBaritone();
                Object cmdManager = baritone.getClass()
                        .getMethod("getCommandManager").invoke(baritone);
                String cmd = String.format("goto %d %d %d",
                        target.getX(), target.getY(), target.getZ());
                cmdManager.getClass()
                        .getMethod("execute", String.class)
                        .invoke(cmdManager, cmd);
            } catch (Exception e2) {
                setState(State.FAILED);
            }
        }
    }

    private void cancelBaritonePathing() {
        try {
            Object baritone = getBaritone();
            // Cancel via pathing behavior
            Object pathingBehavior = baritone.getClass()
                    .getMethod("getPathingBehavior").invoke(baritone);
            pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
            // Also reset custom goal process
            Object customGoalProcess = baritone.getClass()
                    .getMethod("getCustomGoalProcess").invoke(baritone);
            customGoalProcess.getClass().getMethod("onLostControl").invoke(customGoalProcess);
        } catch (Exception ignored) {}
    }

    private boolean isBaritonePathing() {
        try {
            Object baritone = getBaritone();
            Object pathingBehavior = baritone.getClass()
                    .getMethod("getPathingBehavior").invoke(baritone);
            Boolean isPathing = (Boolean) pathingBehavior.getClass()
                    .getMethod("isPathing").invoke(pathingBehavior);
            return isPathing == null ? false : isPathing;
        } catch (Exception e) {
            return false;
        }
    }

    private void openContainerAt(BlockPos target) {
        openContainerWithRetry(target, false, 0);
    }

    /**
     * Opens a container by sending an explicit BlockHitResult via interactBlock().
     * This bypasses client-side raycasting, so adjacent containers do not interfere.
     * Fully vanilla — sends the same interaction packet as a normal right-click.
     */
    private void openContainerWithRetry(BlockPos target, boolean jumpBeforeClick, int attemptNumber) {
        currentContainerTarget = target;
        openAttemptCount = attemptNumber;

        if (jumpBeforeClick && client.player != null) {
            client.player.jump();
        }

        try {
            // Turn the player to face roughly toward the container block.
            // The anti-cheat expects the player to be looking roughly at the target.
            Vec3d playerEye = client.player.getEyePos();
            double dx = target.getX() + 0.5 - playerEye.x;
            double dy = target.getY() + 0.5 - playerEye.y;
            double dz = target.getZ() + 0.5 - playerEye.z;
            double distH = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, distH));
            client.player.setYaw(yaw);
            client.player.setHeadYaw(yaw);
            client.player.setPitch(pitch);

            // Compute the nearest face of the container block
            Direction face = getNearestContainerFace(target);
            Vec3d hitPos = new Vec3d(
                    target.getX() + 0.5 + face.getOffsetX() * 0.5,
                    target.getY() + 0.5 + face.getOffsetY() * 0.5,
                    target.getZ() + 0.5 + face.getOffsetZ() * 0.5
            );

            // Build an explicit BlockHitResult — this is the key:
            // we tell the interaction system exactly WHICH block and face to hit,
            // regardless of client-side raytrace.
            BlockHitResult hitResult = new BlockHitResult(hitPos, face, target, false);

            // Direct API call: sends the interaction packet to the server
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

            transferCooldown = 10;
            containerJustOpened = true;

        } catch (Exception e) {
            // Fallback: vanilla right click
            try {
                client.options.useKey.setPressed(true);
                transferCooldown = 6;
                containerJustOpened = true;
            } catch (Exception ignored) {}
        }
    }

    /**
     * Returns the direction of the container face nearest to the player.
     */
    private Direction getNearestContainerFace(BlockPos target) {
        if (client.player == null) return Direction.UP;
        Vec3d playerEye = client.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);

        double dx = playerEye.x - center.x;
        double dy = playerEye.y - center.y;
        double dz = playerEye.z - center.z;

        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Calculates the centre point of the container face closest to the player.
     * This ensures the right-click hits the intended container, not an adjacent one.
     */
    /**
     * Builds a list of adjacent positions to try if the target container is wrong or blocked.
     */
    private List<BlockPos> getAdjacentContainerTargets(BlockPos target) {
        List<BlockPos> adj = new ArrayList<>();
        // Original target first (retry with fresh precise aim)
        adj.add(target);
        // Then 1-block neighbours in each direction
        adj.add(target.west());
        adj.add(target.east());
        adj.add(target.north());
        adj.add(target.south());
        adj.add(target.up());
        adj.add(target.down());
        return adj;
    }

    /**
     * Checks whether the currently open container screen contains the target item.
     */
    /**
     * Called when the current container target fails to open or has wrong contents.
     * Tries adjacent positions (to handle adjacent containers or blocked faces).
     * Falls back to the next search result if all adjacent positions fail.
     */
    private void retryAdjacentOrFail() {
        // Initialize adjacent position list on first call
        if (adjacentContainerTargets == null) {
            adjacentContainerTargets = getAdjacentContainerTargets(currentContainerTarget);
            adjacentTryIndex = 0;
        }

        // Try the next position in the list
        while (adjacentTryIndex < adjacentContainerTargets.size()) {
            BlockPos adjPos = adjacentContainerTargets.get(adjacentTryIndex);
            adjacentTryIndex++;
            openAttemptCount = 0;
            navigateToContainer(adjPos);
            return;
        }

        // All adjacent positions exhausted — fall back to next search result
        adjacentContainerTargets = null;
        adjacentTryIndex = 0;
        openAttemptCount = 0;
        chestRetryCount++;
        if (chestRetryCount >= 3) {
            chestRetryCount = 0;
            currentPosIndex++;
            if (currentPosIndex >= foundPositions.size()) {
                skipCurrentItem();
            } else {
                navigateToContainer(foundPositions.get(currentPosIndex));
            }
        } else {
            setState(State.SEARCHING);
        }
    }

    /**
     * Navigate to a container position: skip pathing if already nearby.
     */
    private void navigateToContainer(BlockPos pos) {
        if (isPlayerNearPosition(pos, 5.0)) {
            setState(State.OPENING_CONTAINER);
            openContainerAt(pos);
        } else {
            setState(State.PATHING);
            startBaritonePathing(pos);
        }
    }

    /**
     * Checks whether the currently open container has ANY item from the missing items list.
     * If yes, we can transfer from it even if it's not the container we originally targeted.
     */
    private boolean containerHasAnyMissingItem() {
        if (client.player == null || client.player.currentScreenHandler == null) return false;
        List<Slot> slots = client.player.currentScreenHandler.slots;
        for (Slot slot : slots) {
            if (slot.inventory == client.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            for (MaterialItemEntry entry : missingItems) {
                if (itemsMatch(stack, entry.item)) return true;
            }
            // Only count shulker box if we can verify it contains a needed item
            if (isShulkerBox(stack) && shulkerBoxContainsAnyMissingItem(stack)) return true;
        }
        return false;
    }

    /**
     * Checks whether a shulker box item contains any item from the missing list.
     * Uses the 1.21.4 component system (via Yarn imports) to peek inside without opening.
     */
    private boolean shulkerBoxContainsAnyMissingItem(ItemStack shulkerBox) {
        ContainerComponent container = shulkerBox.getComponents().get(DataComponentTypes.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : container.iterateNonEmpty()) {
            for (MaterialItemEntry entry : missingItems) {
                if (itemsMatch(inner, entry.item)) return true;
            }
        }
        return false;
    }

    private void closeAnyContainer() {
        if (client.player != null && client.currentScreen instanceof HandledScreen) {
            client.player.closeHandledScreen();
        }
    }

    public void onWorldChange() {
        if (active) {
            stop();
            MessageUtil.sendActionBar(client, "playercontrolpp.message.baritone.world_change");
        }
    }

    // ---- Data class ----
    private static class MaterialItemEntry {
        final Item item;
        final int neededCount;
        final int maxStackSize;

        MaterialItemEntry(Item item, int neededCount, int maxStackSize) {
            this.item = item;
            this.neededCount = neededCount;
            this.maxStackSize = maxStackSize > 0 ? maxStackSize : 64;
        }
    }
}
