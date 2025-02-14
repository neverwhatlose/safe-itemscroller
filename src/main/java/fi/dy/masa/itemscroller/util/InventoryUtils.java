package fi.dy.masa.itemscroller.util;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import org.apache.commons.lang3.math.Fraction;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.game.wrap.GameWrap;
import fi.dy.masa.itemscroller.ItemScroller;
import fi.dy.masa.itemscroller.config.Configs;
import fi.dy.masa.itemscroller.config.Hotkeys;
import fi.dy.masa.itemscroller.mixin.IMixinCraftingResultSlot;
import fi.dy.masa.itemscroller.recipes.CraftingHandler;
import fi.dy.masa.itemscroller.recipes.CraftingHandler.SlotRange;
import fi.dy.masa.itemscroller.recipes.RecipePattern;
import fi.dy.masa.itemscroller.recipes.RecipeStorage;

public class InventoryUtils
{
    private static final Set<Integer> DRAGGED_SLOTS = new HashSet<>();
    private static final int SERVER_SYNC_MAGIC = 45510;
    public static int dontUpdateRecipeBook;

    private static WeakReference<Slot> sourceSlotCandidate = null;
    private static WeakReference<Slot> sourceSlot = null;
    private static ItemStack stackInCursorLast = ItemStack.EMPTY;
    @Nullable protected static CraftingRecipe lastRecipe;
    private static MoveAction activeMoveAction = MoveAction.NONE;
    private static int lastPosX;
    private static int lastPosY;
    private static int slotNumberLast;
    private static boolean inhibitCraftResultUpdate;
    private static Runnable selectedSlotUpdateTask;
    public static boolean assumeEmptyShulkerStacking = false;
    private static List<String> topSortingPriorityList = Configs.Generic.SORT_TOP_PRIORITY_INVENTORY.getStrings();
    private static List<String> bottomSortingPriorityList = Configs.Generic.SORT_BOTTOM_PRIORITY_INVENTORY.getStrings();
    public static boolean bufferInvUpdates = false;
    public static List<Packet<ClientPlayPacketListener>> invUpdatesBuffer = new ArrayList<>();
    private static ItemGroup.DisplayContext displayContext;

    /*
    private static Pair<Integer, Integer> lastSwapTry = Pair.of(-1, -1);
    private static int repeatedSwaps = 0;
    private static int MAX_REPEATED = 5;
    private static List<Pair<Integer, Integer>> hotbarSwaps = new ArrayList<>();
     */

    public static void setInhibitCraftingOutputUpdate(boolean inhibitUpdate)
    {
        inhibitCraftResultUpdate = inhibitUpdate;
    }

    public static void onSlotChangedCraftingGrid(PlayerEntity player,
                                                 RecipeInputInventory craftMatrix,
                                                 CraftingResultInventory inventoryCraftResult)
    {
//        if (inhibitCraftResultUpdate && Configs.Generic.MASS_CRAFT_INHIBIT_MID_UPDATES.getBooleanValue())
//        {
//            return;
//        }

        if (Configs.Generic.CLIENT_CRAFTING_FIX.getBooleanValue())
        {
            updateCraftingOutputSlot(player, craftMatrix, inventoryCraftResult, true);
        }
    }

    public static void updateCraftingOutputSlot(Slot outputSlot)
    {
        PlayerEntity player = MinecraftClient.getInstance().player;

        if (player != null &&
            outputSlot instanceof CraftingResultSlot resultSlot &&
            resultSlot.inventory instanceof CraftingResultInventory resultInv)
        {
            RecipeInputInventory craftingInv = ((IMixinCraftingResultSlot) outputSlot).itemscroller_getCraftingInventory();
            updateCraftingOutputSlot(player, craftingInv, resultInv, true);
        }
    }

    public static void updateCraftingOutputSlot(PlayerEntity player,
                                                RecipeInputInventory craftMatrix,
                                                CraftingResultInventory inventoryCraftResult,
                                                boolean setEmptyStack)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerWorld serverWorld = mc.getServer() != null ? mc.getServer().getWorld(mc.world.getRegistryKey()) : null;
        World world = player.getEntityWorld();

        if ((world instanceof ClientWorld) && player instanceof ClientPlayerEntity)
        {
            ItemStack stack = ItemStack.EMPTY;
            CraftingRecipe recipe = Configs.Generic.USE_RECIPE_CACHING.getBooleanValue() ? lastRecipe : null;
            RecipeEntry<?> recipeEntry = null;
            CraftingRecipeInput recipeInput = craftMatrix.createRecipeInput();

            if ((recipe == null || recipe.matches(recipeInput, world) == false) &&
                (serverWorld != null))
            {
                Optional<RecipeEntry<CraftingRecipe>> opt = serverWorld.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, recipeInput, serverWorld);
                recipe = opt.map(RecipeEntry::value).orElse(null);
                recipeEntry = opt.orElse(null);
            }

            if (recipe != null)
            {
                GameRules rules = new GameRules(((ClientPlayerEntity) player).networkHandler.getEnabledFeatures());

                if ((recipe.isIgnoredInRecipeBook() ||
                    rules.getBoolean(GameRules.DO_LIMITED_CRAFTING) == false))
                {
                    inventoryCraftResult.setLastRecipe(recipeEntry);
                    stack = recipe.craft(recipeInput, world.getRegistryManager());
                }

                if (setEmptyStack || stack.isEmpty() == false)
                {
                    inventoryCraftResult.setStack(0, stack);
                }
            }

            lastRecipe = recipe;
        }
    }

    public static String getStackString(ItemStack stack)
    {
        if (isStackEmpty(stack) == false)
        {
            Identifier rl = Registries.ITEM.getId(stack.getItem());
            String idStr = rl != null ? rl.toString() : "null";
            String displayName = stack.getName().getString();
            String nbtStr = stack.getComponents() != null ? stack.getComponents().toString() : "<no NBT>";

            return String.format("[%s - display: %s - NBT: %s] (%s)", idStr, displayName, nbtStr, stack);
        }

        return "<empty>";
    }

    public static void debugPrintSlotInfo(HandledScreen<? extends ScreenHandler> gui, Slot slot)
    {
        if (slot == null)
        {
            ItemScroller.logger.info("slot was null");
            return;
        }

        boolean hasSlot = gui.getScreenHandler().slots.contains(slot);
        Object inv = slot.inventory;
        String stackStr = InventoryUtils.getStackString(slot.getStack());

        ItemScroller.logger.info(String.format("slot: slotNumber: %d, getSlotIndex(): %d, getHasStack(): %s, " +
                "slot class: %s, inv class: %s, Container's slot list has slot: %s, stack: %s, numSlots: %d",
                slot.id, AccessorUtils.getSlotIndex(slot), slot.hasStack(), slot.getClass().getName(),
                inv != null ? inv.getClass().getName() : "<null>", hasSlot ? " true" : "false", stackStr,
                gui.getScreenHandler().slots.size()));
    }

    private static boolean isValidSlot(Slot slot, HandledScreen<? extends ScreenHandler> gui, boolean requireItems)
    {
        ScreenHandler container = gui.getScreenHandler();

        return container != null && container.slots != null &&
                slot != null && container.slots.contains(slot) &&
                (requireItems == false || slot.hasStack()) &&
                Configs.SLOT_BLACKLIST.contains(slot.getClass().getName()) == false;
    }

    public static boolean isCraftingSlot(HandledScreen<? extends ScreenHandler> gui, @Nullable Slot slot)
    {
        return slot != null && CraftingHandler.getCraftingGridSlots(gui, slot) != null;
    }

    /**
     * Checks if there are slots belonging to another inventory on screen above the given slot
     */
    private static boolean inventoryExistsAbove(Slot slot, ScreenHandler container)
    {
        for (Slot slotTmp : container.slots)
        {
            if (slotTmp.y < slot.y && areSlotsInSameInventory(slot, slotTmp) == false)
            {
                return true;
            }
        }

        return false;
    }

    public static boolean canShiftPlaceItems(HandledScreen<? extends ScreenHandler> gui)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

        // The target slot needs to be an empty, valid slot, and there needs to be items in the cursor
        return slot != null && isStackEmpty(stackCursor) == false && isValidSlot(slot, gui, false) &&
               slot.hasStack() == false && slot.canInsert(stackCursor);
    }

    public static boolean tryMoveItems(HandledScreen<? extends ScreenHandler> gui,
                                       RecipeStorage recipes,
                                       boolean scrollingUp)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);

        // We require an empty cursor
        if (slot == null || isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            return false;
        }

        // Villager handling only happens when scrolling over the trade output slot
        boolean villagerHandling = Configs.Toggles.SCROLL_VILLAGER.getBooleanValue() && gui instanceof MerchantScreen && slot instanceof TradeOutputSlot;
        boolean craftingHandling = Configs.Toggles.CRAFTING_FEATURES.getBooleanValue() && isCraftingSlot(gui, slot);
        boolean keyActiveMoveEverything = Hotkeys.MODIFIER_MOVE_EVERYTHING.getKeybind().isKeybindHeld();
        boolean keyActiveMoveMatching = Hotkeys.MODIFIER_MOVE_MATCHING.getKeybind().isKeybindHeld();
        boolean keyActiveMoveStacks = Hotkeys.MODIFIER_MOVE_STACK.getKeybind().isKeybindHeld();
        boolean nonSingleMove = keyActiveMoveEverything || keyActiveMoveMatching || keyActiveMoveStacks;
        boolean moveToOtherInventory = scrollingUp;

        if (Configs.Generic.SLOT_POSITION_AWARE_SCROLL_DIRECTION.getBooleanValue())
        {
            boolean above = inventoryExistsAbove(slot, gui.getScreenHandler());
            // so basically: (above && scrollingUp) || (above == false && scrollingUp == false)
            moveToOtherInventory = (above == scrollingUp);
        }

        if ((Configs.Generic.REVERSE_SCROLL_DIRECTION_SINGLE.getBooleanValue() && nonSingleMove == false) ||
            (Configs.Generic.REVERSE_SCROLL_DIRECTION_STACKS.getBooleanValue() && nonSingleMove))
        {
            moveToOtherInventory = ! moveToOtherInventory;
        }

        // Check that the slot is valid, (don't require items in case of the villager output slot or a crafting slot)
        if (isValidSlot(slot, gui, villagerHandling == false && craftingHandling == false) == false)
        {
            return false;
        }

        if (craftingHandling)
        {
            return tryMoveItemsCrafting(recipes, slot, gui, moveToOtherInventory, keyActiveMoveStacks, keyActiveMoveEverything);
        }

        if (villagerHandling)
        {
            return tryMoveItemsVillager((MerchantScreen) gui, slot, moveToOtherInventory, keyActiveMoveStacks);
        }

        if ((Configs.Toggles.SCROLL_SINGLE.getBooleanValue() == false && nonSingleMove == false) ||
            (Configs.Toggles.SCROLL_STACKS.getBooleanValue() == false && keyActiveMoveStacks) ||
            (Configs.Toggles.SCROLL_MATCHING.getBooleanValue() == false && keyActiveMoveMatching) ||
            (Configs.Toggles.SCROLL_EVERYTHING.getBooleanValue() == false && keyActiveMoveEverything))
        {
            return false;
        }

        // Move everything
        if (keyActiveMoveEverything)
        {
            tryMoveStacks(slot, gui, false, moveToOtherInventory, false);
        }
        // Move all matching items
        else if (keyActiveMoveMatching)
        {
            tryMoveStacks(slot, gui, true, moveToOtherInventory, false);
            return true;
        }
        // Move one matching stack
        else if (keyActiveMoveStacks)
        {
            tryMoveStacks(slot, gui, true, moveToOtherInventory, true);
        }
        else
        {
            ItemStack stack = slot.getStack();

            // Scrolling items from this slot/inventory into the other inventory
            if (moveToOtherInventory)
            {
                tryMoveSingleItemToOtherInventory(slot, gui);
            }
            // Scrolling items from the other inventory into this slot/inventory
            else if (getStackSize(stack) < slot.getMaxItemCount(stack))
            {
                tryMoveSingleItemToThisInventory(slot, gui);
            }
        }

        return false;
    }

    public static boolean dragMoveItems(HandledScreen<? extends ScreenHandler> gui,
                                        MoveAction action,
                                        int mouseX, int mouseY, boolean isClick)
    {
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            // Updating these here is part of the fix to preventing a drag after shift + place
            lastPosX = mouseX;
            lastPosY = mouseY;
            stopDragging();

            return false;
        }

        boolean cancel = false;

        if (isClick && action != MoveAction.NONE)
        {
            // Reset this or the method call won't do anything...
            slotNumberLast = -1;
            lastPosX = mouseX;
            lastPosY = mouseY;
            activeMoveAction = action;
            cancel = dragMoveFromSlotAtPosition(gui, mouseX, mouseY, action);
        }
        else
        {
            action = activeMoveAction;
        }

        if (activeMoveAction != MoveAction.NONE && cancel == false)
        {
            int distX = mouseX - lastPosX;
            int distY = mouseY - lastPosY;
            int absX = Math.abs(distX);
            int absY = Math.abs(distY);

            if (absX > absY)
            {
                int inc = distX > 0 ? 1 : -1;

                for (int x = lastPosX; ; x += inc)
                {
                    int y = absX != 0 ? lastPosY + ((x - lastPosX) * distY / absX) : mouseY;
                    dragMoveFromSlotAtPosition(gui, x, y, action);

                    if (x == mouseX)
                    {
                        break;
                    }
                }
            }
            else
            {
                int inc = distY > 0 ? 1 : -1;

                for (int y = lastPosY; ; y += inc)
                {
                    int x = absY != 0 ? lastPosX + ((y - lastPosY) * distX / absY) : mouseX;
                    dragMoveFromSlotAtPosition(gui, x, y, action);

                    if (y == mouseY)
                    {
                        break;
                    }
                }
            }
        }

        lastPosX = mouseX;
        lastPosY = mouseY;

        // Always update the slot under the mouse.
        // This should prevent a "double click/move" when shift + left clicking on slots that have more
        // than one stack of items. (the regular slotClick() + a "drag move" from the slot that is under the mouse
        // when the left mouse button is pressed down and this code runs).
        Slot slot = AccessorUtils.getSlotAtPosition(gui, mouseX, mouseY);

        if (slot != null)
        {
            if (gui instanceof CreativeInventoryScreen)
            {
                boolean isPlayerInv = ((CreativeInventoryScreen) gui).isInventoryTabSelected(); // TODO 1.19.3+
                int slotNumber = isPlayerInv ? AccessorUtils.getSlotIndex(slot) : slot.id;
                slotNumberLast = slotNumber;
            }
            else
            {
                slotNumberLast = slot.id;
            }
        }
        else
        {
            slotNumberLast = -1;
        }

        return cancel;
    }

    public static void stopDragging()
    {
        activeMoveAction = MoveAction.NONE;
        DRAGGED_SLOTS.clear();
    }

    private static boolean dragMoveFromSlotAtPosition(HandledScreen<? extends ScreenHandler> gui,
                                                      int x, int y, MoveAction action)
    {
        if (gui instanceof CreativeInventoryScreen)
        {
            return dragMoveFromSlotAtPositionCreative(gui, x, y, action);
        }

        Slot slot = AccessorUtils.getSlotAtPosition(gui, x, y);
        MinecraftClient mc = MinecraftClient.getInstance();
        MoveAmount amount = InputUtils.getMoveAmount(action);
        boolean flag = slot != null && isValidSlot(slot, gui, true) && slot.canTakeItems(mc.player);
        //boolean cancel = flag && (amount == MoveAmount.LEAVE_ONE || amount == MoveAmount.MOVE_ONE);

        if (flag && slot.id != slotNumberLast &&
            (amount != MoveAmount.MOVE_ONE || DRAGGED_SLOTS.contains(slot.id) == false))
        {
            switch (action)
            {
                case MOVE_TO_OTHER_MOVE_ONE:
                    tryMoveSingleItemToOtherInventory(slot, gui);
                    break;

                case MOVE_TO_OTHER_LEAVE_ONE:
                    tryMoveAllButOneItemToOtherInventory(slot, gui);
                    break;

                case MOVE_TO_OTHER_STACKS:
                    shiftClickSlot(gui, slot.id);
                    break;

                case MOVE_TO_OTHER_MATCHING:
                    tryMoveStacks(slot, gui, true, true, false);
                    break;

                case DROP_ONE:
                    clickSlot(gui, slot.id, 0, SlotActionType.THROW);
                    break;

                case DROP_LEAVE_ONE:
                    leftClickSlot(gui, slot.id);
                    rightClickSlot(gui, slot.id);
                    dropItemsFromCursor(gui);
                    break;

                case DROP_STACKS:
                    clickSlot(gui, slot.id, 1, SlotActionType.THROW);
                    break;

                case MOVE_DOWN_MOVE_ONE:
                case MOVE_DOWN_LEAVE_ONE:
                case MOVE_DOWN_STACKS:
                case MOVE_DOWN_MATCHING:
                    tryMoveItemsVertically(gui, slot, false, amount);
                    break;

                case MOVE_UP_MOVE_ONE:
                case MOVE_UP_LEAVE_ONE:
                case MOVE_UP_STACKS:
                case MOVE_UP_MATCHING:
                    tryMoveItemsVertically(gui, slot, true, amount);
                    break;

                default:
            }

            DRAGGED_SLOTS.add(slot.id);
        }

        return true;
    }

    private static boolean dragMoveFromSlotAtPositionCreative(HandledScreen<? extends ScreenHandler> gui,
                                                              int x, int y, MoveAction action)
    {
        CreativeInventoryScreen guiCreative = (CreativeInventoryScreen) gui;
        Slot slot = AccessorUtils.getSlotAtPosition(gui, x, y);
        boolean isPlayerInv = guiCreative.isInventoryTabSelected(); // TODO 1.19.3+

        // Only allow dragging from the hotbar slots
        if (slot == null || (slot.getClass() != Slot.class && isPlayerInv == false))
        {
            return false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        MoveAmount amount = InputUtils.getMoveAmount(action);
        boolean flag = slot != null && isValidSlot(slot, gui, true) && slot.canTakeItems(mc.player);
        boolean cancel = flag && (amount == MoveAmount.LEAVE_ONE || amount == MoveAmount.MOVE_ONE);
        // The player inventory tab of the creative inventory uses stupid wrapped
        // slots that all have slotNumber = 0 on the outer instance ;_;
        // However in that case we can use the slotIndex which is easy enough to get.
        int slotNumber = isPlayerInv ? AccessorUtils.getSlotIndex(slot) : slot.id;

        if (flag && slotNumber != slotNumberLast && DRAGGED_SLOTS.contains(slotNumber) == false)
        {
            switch (action)
            {
                case SCROLL_TO_OTHER_MOVE_ONE:
                case MOVE_TO_OTHER_MOVE_ONE:
                    leftClickSlot(guiCreative, slot, slotNumber);
                    rightClickSlot(guiCreative, slot, slotNumber);
                    shiftClickSlot(guiCreative, slot, slotNumber);
                    leftClickSlot(guiCreative, slot, slotNumber);

                    cancel = true;
                    break;

                case MOVE_TO_OTHER_LEAVE_ONE:
                    // Too lazy to try to duplicate the proper code for the weird creative inventory...
                    if (isPlayerInv == false)
                    {
                        leftClickSlot(guiCreative, slot, slotNumber);
                        rightClickSlot(guiCreative, slot, slotNumber);

                        // Delete the rest of the stack by placing it in the first creative "source slot"
                        Slot slotFirst = gui.getScreenHandler().slots.get(0);
                        leftClickSlot(guiCreative, slotFirst, slotFirst.id);
                    }

                    cancel = true;
                    break;

                case SCROLL_TO_OTHER_STACKS:
                case MOVE_TO_OTHER_STACKS:
                    shiftClickSlot(gui, slot, slotNumber);
                    cancel = true;
                    break;

                case DROP_ONE:
                    clickSlot(gui, slot.id, 0, SlotActionType.THROW);
                    break;

                case DROP_LEAVE_ONE:
                    leftClickSlot(gui, slot.id);
                    rightClickSlot(gui, slot.id);
                    dropItemsFromCursor(gui);
                    break;

                case DROP_STACKS:
                    clickSlot(gui, slot.id, 1, SlotActionType.THROW);
                    cancel = true;
                    break;

                case MOVE_DOWN_MOVE_ONE:
                case MOVE_DOWN_LEAVE_ONE:
                case MOVE_DOWN_STACKS:
                    tryMoveItemsVertically(gui, slot, false, amount);
                    cancel = true;
                    break;

                case MOVE_UP_MOVE_ONE:
                case MOVE_UP_LEAVE_ONE:
                case MOVE_UP_STACKS:
                    tryMoveItemsVertically(gui, slot, true, amount);
                    cancel = true;
                    break;

                default:
            }

            DRAGGED_SLOTS.add(slotNumber);
        }

        return cancel;
    }

    public static void dropStacks(HandledScreen<? extends ScreenHandler> gui,
                                  ItemStack stackReference,
                                  Slot slotReference,
                                  boolean sameInventory)
    {
        if (slotReference != null && isStackEmpty(stackReference) == false)
        {
            ScreenHandler container = gui.getScreenHandler();
            stackReference = stackReference.copy();

            for (Slot slot : container.slots)
            {
                // If this slot is in the same inventory that the items were picked up to the cursor from
                // and the stack is identical to the one in the cursor, then this stack will get dropped.
                if (areSlotsInSameInventory(slot, slotReference) == sameInventory &&
                    areStacksEqual(slot.getStack(), stackReference))
                {
                    // Drop the stack
                    dropStack(gui, slot.id);
                }
            }
        }
    }

    public static void dropAllMatchingStacks(HandledScreen<? extends ScreenHandler> gui,
                                             ItemStack stackReference)
    {
        if (isStackEmpty(stackReference) == false)
        {
            ScreenHandler container = gui.getScreenHandler();
            stackReference = stackReference.copy();

            for (Slot slot : container.slots)
            {
                if (areStacksEqual(slot.getStack(), stackReference))
                {
                    // Drop the stack
                    dropStack(gui, slot.id);
                }
            }
        }
    }

    public static boolean shiftDropItems(HandledScreen<? extends ScreenHandler> gui)
    {
        ItemStack stackReference = gui.getScreenHandler().getCursorStack();

        if (isStackEmpty(stackReference) == false && sourceSlot != null)
        {
            stackReference = stackReference.copy();

            // First drop the existing stack from the cursor
            dropItemsFromCursor(gui);

            dropStacks(gui, stackReference, sourceSlot.get(), true);
            return true;
        }

        return false;
    }

    public static boolean shiftPlaceItems(Slot slot, HandledScreen<? extends ScreenHandler> gui)
    {
        // Left click to place the items from the cursor to the slot
        leftClickSlot(gui, slot.id);

        // Ugly fix to prevent accidentally drag-moving the stack from the slot that it was just placed into...
        DRAGGED_SLOTS.add(slot.id);

        tryMoveStacks(slot, gui, true, false, false);

        return true;
    }

    /**
     * Store a reference to the slot when a slot is left or right clicked on.
     * The slot is then later used to determine which inventory an ItemStack was
     * picked up from, if the stack from the cursor is dropped while holding shift.
     */
    public static void storeSourceSlotCandidate(Slot slot, HandledScreen<?> gui)
    {
        // Left or right mouse button was pressed
        if (slot != null)
        {
            ItemStack stackCursor = gui.getScreenHandler().getCursorStack();
            ItemStack stack = EMPTY_STACK;

            if (isStackEmpty(stackCursor) == false)
            {
                // Do a cheap copy without NBT data
                stack = new ItemStack(stackCursor.getItem(), getStackSize(stackCursor));
            }

            // Store the candidate
            // NOTE: This method is called BEFORE the stack has been picked up to the cursor!
            // Thus we can't check that there is an item already in the cursor, and that's why this is just a "candidate"
            sourceSlotCandidate = new WeakReference<>(slot);
            stackInCursorLast = stack;
        }
    }

    /**
     * Check if the (previous) mouse event resulted in picking up a new ItemStack to the cursor
     */
    public static void checkForItemPickup(HandledScreen<?> gui)
    {
        ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

        // Picked up or swapped items to the cursor, grab a reference to the slot that the items came from
        // Note that we are only checking the item here!
        if (isStackEmpty(stackCursor) == false && ItemStack.areItemsEqual(stackCursor, stackInCursorLast) == false && sourceSlotCandidate != null)
        {
            sourceSlot = new WeakReference<>(sourceSlotCandidate.get());
        }
    }

    private static boolean tryMoveItemsVillager(MerchantScreen gui,
                                                Slot slot,
                                                boolean moveToOtherInventory,
                                                boolean fullStacks)
    {
        if (fullStacks)
        {
            // Try to fill the merchant's buy slots from the player inventory
            if (moveToOtherInventory == false)
            {
                tryMoveItemsToMerchantBuySlots(gui, true);
            }
            // Move items from sell slot to player inventory
            else if (slot.hasStack())
            {
                tryMoveStacks(slot, gui, true, true, true);
            }
            // Scrolling over an empty output slot, clear the buy slots
            else
            {
                tryMoveStacks(slot, gui, false, true, false);
            }
        }
        else
        {
            // Scrolling items from player inventory into merchant buy slots
            if (moveToOtherInventory == false)
            {
                tryMoveItemsToMerchantBuySlots(gui, false);
            }
            // Scrolling items from this slot/inventory into the other inventory
            else if (slot.hasStack())
            {
                moveOneSetOfItemsFromSlotToPlayerInventory(gui, slot);
            }
        }

        return false;
    }

    public static void villagerClearTradeInputSlots()
    {
        if (GuiUtils.getCurrentScreen() instanceof MerchantScreen merchantGui)
        {
            Slot slot = merchantGui.getScreenHandler().getSlot(0);

            if (slot.hasStack())
            {
                shiftClickSlot(merchantGui, slot.id);
            }

            slot = merchantGui.getScreenHandler().getSlot(1);

            if (slot.hasStack())
            {
                shiftClickSlot(merchantGui, slot.id);
            }
        }
    }

    private static boolean tryMoveSingleItemToOtherInventory(Slot slot,
                                                             HandledScreen<? extends ScreenHandler> gui)
    {
        ItemStack stackOrig = slot.getStack();
        ScreenHandler container = gui.getScreenHandler();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false || slot.canTakeItems(mc.player) == false ||
            (getStackSize(stackOrig) > 1 && slot.canInsert(stackOrig) == false))
        {
            return false;
        }

        // Can take all the items to the cursor at once, use a shift-click method to move one item from the slot
        if (getStackSize(stackOrig) <= stackOrig.getMaxCount())
        {
            return clickSlotsToMoveSingleItemByShiftClick(gui, slot.id);
        }

        ItemStack stack = stackOrig.copy();
        setStackSize(stack, 1);

        ItemStack[] originalStacks = getOriginalStacks(container);

        // Try to move the temporary single-item stack via the shift-click handler method
        slot.setStackNoCallbacks(stack);
        container.quickMove(mc.player, slot.id);

        // Successfully moved the item somewhere, now we want to check where it went
        if (slot.hasStack() == false)
        {
            int targetSlot = getTargetSlot(container, originalStacks);

            // Found where the item went
            if (targetSlot >= 0)
            {
                // Remove the dummy item from the target slot (on the client side)
                container.slots.get(targetSlot).takeStack(1);

                // Restore the original stack to the slot under the cursor (on the client side)
                restoreOriginalStacks(container, originalStacks);

                // Do the slot clicks to actually move the items (on the server side)
                return clickSlotsToMoveSingleItem(gui, slot.id, targetSlot);
            }
        }

        // Restore the original stack to the slot under the cursor (on the client side)
        slot.setStackNoCallbacks(stackOrig);

        return false;
    }

    private static boolean tryMoveAllButOneItemToOtherInventory(Slot slot,
                                                                HandledScreen<? extends ScreenHandler> gui)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        ItemStack stackOrig = slot.getStack().copy();

        if (getStackSize(stackOrig) == 1 || getStackSize(stackOrig) > stackOrig.getMaxCount() ||
            slot.canTakeItems(player) == false || slot.canInsert(stackOrig) == false)
        {
            return true;
        }

        // Take half of the items from the original slot to the cursor
        rightClickSlot(gui, slot.id);

        ItemStack stackInCursor = gui.getScreenHandler().getCursorStack();
        if (isStackEmpty(stackInCursor))
        {
            return false;
        }

        int stackInCursorSizeOrig = getStackSize(stackInCursor);
        int tempSlotNum = -1;

        // Find some other slot where to store one of the items temporarily
        for (Slot slotTmp : gui.getScreenHandler().slots)
        {
            if (slotTmp.id != slot.id &&
                areSlotsInSameInventory(slotTmp, slot, true) &&
                slotTmp.canInsert(stackInCursor) &&
                slotTmp.canTakeItems(player))
            {
                ItemStack stackInSlot = slotTmp.getStack();

                if (isStackEmpty(stackInSlot) || areStacksEqual(stackInSlot, stackInCursor))
                {
                    // Try to put one item into the temporary slot
                    rightClickSlot(gui, slotTmp.id);

                    stackInCursor = gui.getScreenHandler().getCursorStack();

                    // Successfully stored one item
                    if (isStackEmpty(stackInCursor) || getStackSize(stackInCursor) < stackInCursorSizeOrig)
                    {
                        tempSlotNum = slotTmp.id;
                        break;
                    }
                }
            }
        }

        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            // Return the rest of the items into the original slot
            leftClickSlot(gui, slot.id);
        }

        // Successfully stored one item in a temporary slot
        if (tempSlotNum != -1)
        {
            // Shift click the stack from the original slot
            shiftClickSlot(gui, slot.id);

            // Take half a stack from the temporary slot
            rightClickSlot(gui, tempSlotNum);

            // Return one item into the original slot
            rightClickSlot(gui, slot.id);

            // Return the rest of the items to the temporary slot, if any
            if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
            {
                leftClickSlot(gui, tempSlotNum);
            }

            return true;
        }
        // No temporary slot found, try to move the stack manually
        else
        {
            boolean treatHotbarAsDifferent = gui.getClass() == InventoryScreen.class;
            IntArrayList slots = getSlotNumbersOfEmptySlots(gui.getScreenHandler(), slot, false, treatHotbarAsDifferent, false);

            if (slots.isEmpty())
            {
                slots = getSlotNumbersOfMatchingStacks(gui.getScreenHandler(), slot, false, slot.getStack(), true, treatHotbarAsDifferent, false);
            }

            if (slots.isEmpty() == false)
            {
                // Take the stack
                leftClickSlot(gui, slot.id);

                // Return one item
                rightClickSlot(gui, slot.id);

                // Try to place the stack in the cursor to any valid empty or matching slots in a different inventory
                for (int slotNum : slots)
                {
                    Slot slotTmp = gui.getScreenHandler().getSlot(slotNum);
                    stackInCursor = gui.getScreenHandler().getCursorStack();

                    if (isStackEmpty(stackInCursor))
                    {
                        return true;
                    }

                    if (slotTmp.canInsert(stackInCursor))
                    {
                        leftClickSlot(gui, slotNum);
                    }
                }

                // Items left, return them
                if (isStackEmpty(stackInCursor) == false)
                {
                    leftClickSlot(gui, slot.id);
                }
            }
        }

        return false;
    }

    private static boolean tryMoveSingleItemToThisInventory(Slot slot,
                                                            HandledScreen<? extends ScreenHandler> gui)
    {
        ScreenHandler container = gui.getScreenHandler();
        ItemStack stackOrig = slot.getStack();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (slot.canInsert(stackOrig) == false)
        {
            return false;
        }

        for (int slotNum = container.slots.size() - 1; slotNum >= 0; slotNum--)
        {
            Slot slotTmp = container.slots.get(slotNum);
            ItemStack stackTmp = slotTmp.getStack();

            if (areSlotsInSameInventory(slotTmp, slot) == false &&
                isStackEmpty(stackTmp) == false && slotTmp.canTakeItems(mc.player) &&
                (getStackSize(stackTmp) == 1 || slotTmp.canInsert(stackTmp)))
            {
                if (areStacksEqual(stackTmp, stackOrig))
                {
                    return clickSlotsToMoveSingleItem(gui, slotTmp.id, slot.id);
                }
            }
        }

        // If we weren't able to move any items from another inventory, then try to move items
        // within the same inventory (mostly between the hotbar and the player inventory)
        /*
        for (Slot slotTmp : container.slots)
        {
            ItemStack stackTmp = slotTmp.getStack();

            if (slotTmp.id != slot.id &&
                isStackEmpty(stackTmp) == false && slotTmp.canTakeItems(gui.mc.player) &&
                (getStackSize(stackTmp) == 1 || slotTmp.canInsert(stackTmp)))
            {
                if (areStacksEqual(stackTmp, stackOrig))
                {
                    return this.clickSlotsToMoveSingleItem(gui, slotTmp.id, slot.id);
                }
            }
        }
        */

        return false;
    }

    public static void tryMoveStacks(Slot slot,
                                     HandledScreen<? extends ScreenHandler> gui,
                                     boolean matchingOnly,
                                     boolean toOtherInventory,
                                     boolean firstOnly)
    {
        tryMoveStacks(slot.getStack(), slot, gui, matchingOnly, toOtherInventory, firstOnly);
    }

    private static void tryMoveStacks(ItemStack stackReference,
                                      Slot slot,
                                      HandledScreen<? extends ScreenHandler> gui,
                                      boolean matchingOnly,
                                      boolean toOtherInventory,
                                      boolean firstOnly)
    {
        ScreenHandler container = gui.getScreenHandler();
        final int maxSlot = container.slots.size() - 1;

        for (int i = maxSlot; i >= 0; i--)
        {
            Slot slotTmp = container.slots.get(i);

            if (slotTmp.id != slot.id &&
                areSlotsInSameInventory(slotTmp, slot) == toOtherInventory && slotTmp.hasStack() &&
                (matchingOnly == false || areStacksEqual(stackReference, slotTmp.getStack())))
            {
                boolean success = shiftClickSlotWithCheck(gui, slotTmp.id);

                // Failed to shift-click items, try a manual method
                if (success == false && Configs.Toggles.SCROLL_STACKS_FALLBACK.getBooleanValue())
                {
                    clickSlotsToMoveItemsFromSlot(slotTmp, gui, toOtherInventory);
                }

                if (firstOnly)
                {
                    return;
                }
            }
        }

        // If moving to the other inventory, then move the hovered slot's stack last
        if (toOtherInventory &&
            shiftClickSlotWithCheck(gui, slot.id) == false &&
            Configs.Toggles.SCROLL_STACKS_FALLBACK.getBooleanValue())
        {
            clickSlotsToMoveItemsFromSlot(slot, gui, toOtherInventory);
        }
    }

    private static void tryMoveItemsToMerchantBuySlots(MerchantScreen gui,
                                                       boolean fillStacks)
    {
        TradeOfferList list = gui.getScreenHandler().getRecipes();
        int index = AccessorUtils.getSelectedMerchantRecipe(gui);

        if (list == null || list.size() <= index)
        {
            return;
        }

        TradeOffer recipe = list.get(index);

        if (recipe == null)
        {
            return;
        }

        ItemStack buy1 = recipe.getDisplayedFirstBuyItem();
        ItemStack buy2 = recipe.getDisplayedSecondBuyItem();

        if (isStackEmpty(buy1) == false)
        {
            fillBuySlot(gui, 0, buy1, fillStacks);
        }

        if (isStackEmpty(buy2) == false)
        {
            fillBuySlot(gui, 1, buy2, fillStacks);
        }
    }

    private static void fillBuySlot(HandledScreen<? extends ScreenHandler> gui,
                                    int slotNum,
                                    ItemStack buyStack,
                                    boolean fillStacks)
    {
        Slot slot = gui.getScreenHandler().getSlot(slotNum);
        ItemStack existingStack = slot.getStack();
        MinecraftClient mc = MinecraftClient.getInstance();

        // If there are items not matching the merchant recipe, move them out first
        if (isStackEmpty(existingStack) == false && areStacksEqual(buyStack, existingStack) == false)
        {
            shiftClickSlot(gui, slotNum);
        }

        existingStack = slot.getStack();

        if (isStackEmpty(existingStack) || areStacksEqual(buyStack, existingStack))
        {
            moveItemsFromInventory(gui, slotNum, mc.player.getInventory(), buyStack, fillStacks);
        }
    }

    public static void handleRecipeClick(HandledScreen<? extends ScreenHandler> gui,
                                         MinecraftClient mc,
                                         RecipeStorage recipes,
                                         int hoveredRecipeId,
                                         boolean isLeftClick,
                                         boolean isRightClick,
                                         boolean isPickBlock,
                                         boolean isShiftDown)
    {
        if (isLeftClick || isRightClick)
        {
            boolean changed = recipes.getSelection() != hoveredRecipeId;
            recipes.changeSelectedRecipe(hoveredRecipeId);

            if (changed)
            {
                InventoryUtils.clearFirstCraftingGridOfItems(recipes.getSelectedRecipe(), gui, false);
            }
            else
            {
                InventoryUtils.tryMoveItemsToFirstCraftingGrid(recipes.getRecipe(hoveredRecipeId), gui, isShiftDown);
            }

            // Right click: Also craft the items
            if (isRightClick)
            {
                Slot outputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);
                boolean dropKeyDown = mc.options.dropKey.isPressed(); // FIXME 1.14

                if (outputSlot != null)
                {
                    if (dropKeyDown)
                    {
                        if (isShiftDown)
                        {
                            if (Configs.Generic.CARPET_CTRL_Q_CRAFTING.getBooleanValue())
                            {
                                InventoryUtils.dropStack(gui, outputSlot.id);
                            }
                            else
                            {
                                InventoryUtils.dropStacksUntilEmpty(gui, outputSlot.id);
                            }
                        }
                        else
                        {
                            InventoryUtils.dropItem(gui, outputSlot.id);
                        }
                    }
                    else
                    {
                        if (isShiftDown)
                        {
                            InventoryUtils.shiftClickSlot(gui, outputSlot.id);
                        }
                        else
                        {
                            InventoryUtils.moveOneSetOfItemsFromSlotToPlayerInventory(gui, outputSlot);
                        }
                    }
                }
            }
        }
        else if (isPickBlock)
        {
            InventoryUtils.clearFirstCraftingGridOfAllItems(gui);
        }
    }

    public static void tryMoveItemsToFirstCraftingGrid(RecipePattern recipe,
                                                       HandledScreen<? extends ScreenHandler> gui,
                                                       boolean fillStacks)
    {
        Slot craftingOutputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (craftingOutputSlot != null)
        {
            tryMoveItemsToCraftingGridSlots(recipe, craftingOutputSlot, gui, fillStacks);
        }
    }

    public static void loadRecipeItemsToGridForOutputSlotUnderMouse(RecipePattern recipe,
                                                                    HandledScreen<? extends ScreenHandler> gui)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        loadRecipeItemsToGridForOutputSlot(recipe, gui, slot);
    }

    private static void loadRecipeItemsToGridForOutputSlot(RecipePattern recipe,
                                                           HandledScreen<? extends ScreenHandler> gui,
                                                           Slot outputSlot)
    {
        if (isCraftingSlot(gui, outputSlot) && isStackEmpty(recipe.getResult()) == false)
        {
            tryMoveItemsToCraftingGridSlots(recipe, outputSlot, gui, false);
        }
    }

    private static boolean tryMoveItemsCrafting(RecipeStorage recipes,
                                                Slot slot,
                                                HandledScreen<? extends ScreenHandler> gui,
                                                boolean moveToOtherInventory,
                                                boolean moveStacks,
                                                boolean moveEverything)
    {
        RecipePattern recipe = recipes.getSelectedRecipe();
        ItemStack stackRecipeOutput = recipe.getResult();

        // Try to craft items
        if (moveToOtherInventory)
        {
            // Items in the output slot
            if (slot.hasStack())
            {
                // The output item matches the current recipe
                if (areStacksEqual(slot.getStack(), stackRecipeOutput))
                {
                    if (moveEverything)
                    {
                        craftAsManyItemsAsPossible(recipe, slot, gui);
                    }
                    else if (moveStacks)
                    {
                        shiftClickSlot(gui, slot.id);
                    }
                    else
                    {
                        moveOneSetOfItemsFromSlotToPlayerInventory(gui, slot);
                    }
                }
            }
            // Scrolling over an empty output slot, clear the grid
            else
            {
                clearCraftingGridOfAllItems(gui, CraftingHandler.getCraftingGridSlots(gui, slot));
            }
        }
        // Try to move items to the grid
        else if (moveToOtherInventory == false && isStackEmpty(stackRecipeOutput) == false)
        {
            tryMoveItemsToCraftingGridSlots(recipe, slot, gui, moveStacks);
        }

        return false;
    }

    private static void craftAsManyItemsAsPossible(RecipePattern recipe,
                                                   Slot slot,
                                                   HandledScreen<? extends ScreenHandler> gui)
    {
        ItemStack result = recipe.getResult();
        int failSafe = 1024;

        while (failSafe > 0 && slot.hasStack() && areStacksEqual(slot.getStack(), result))
        {
            shiftClickSlot(gui, slot.id);

            // Ran out of some or all ingredients for the recipe
            if (slot.hasStack() == false || areStacksEqual(slot.getStack(), result) == false)
            {
                tryMoveItemsToCraftingGridSlots(recipe, slot, gui, true);
            }
            // No change in the result slot after shift clicking, let's assume the craft failed and stop here
            else
            {
                break;
            }

            failSafe--;
        }
    }

    public static void clearFirstCraftingGridOfItems(RecipePattern recipe,
                                                     HandledScreen<? extends ScreenHandler> gui,
                                                     boolean clearNonMatchingOnly)
    {
        Slot craftingOutputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (craftingOutputSlot != null)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, craftingOutputSlot);
            clearCraftingGridOfItems(recipe, gui, range, clearNonMatchingOnly);
        }
    }

    public static void clearFirstCraftingGridOfAllItems(HandledScreen<? extends ScreenHandler> gui)
    {
        Slot craftingOutputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (craftingOutputSlot != null)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, craftingOutputSlot);
            clearCraftingGridOfAllItems(gui, range);
        }
    }

    private static boolean clearCraftingGridOfItems(RecipePattern recipe,
                                                    HandledScreen<? extends ScreenHandler> gui,
                                                    SlotRange range,
                                                    boolean clearNonMatchingOnly)
    {
        final int invSlots = gui.getScreenHandler().slots.size();
        final int rangeSlots = range.getSlotCount();
        final int recipeSize = recipe.getRecipeLength();
        final int slotCount = Math.min(rangeSlots, recipeSize);

        for (int i = 0, slotNum = range.getFirst(); i < slotCount && slotNum < invSlots; i++, slotNum++)
        {
            Slot slotTmp = gui.getScreenHandler().getSlot(slotNum);

            if (slotTmp != null && slotTmp.hasStack() &&
                (clearNonMatchingOnly == false || areStacksEqual(recipe.getRecipeItems()[i], slotTmp.getStack()) == false))
            {
                shiftClickSlot(gui, slotNum);

                // Failed to clear the slot
                if (slotTmp.hasStack())
                {
                    dropStack(gui, slotNum);
                }
            }
        }

        return true;
    }

    private static boolean clearCraftingGridOfAllItems(HandledScreen<? extends ScreenHandler> gui, SlotRange range)
    {
        final int invSlots = gui.getScreenHandler().slots.size();
        final int rangeSlots = range.getSlotCount();
        boolean clearedAll = true;

        for (int i = 0, slotNum = range.getFirst(); i < rangeSlots && slotNum < invSlots; i++, slotNum++)
        {
            Slot slotTmp = gui.getScreenHandler().getSlot(slotNum);

            if (slotTmp != null && slotTmp.hasStack())
            {
                shiftClickSlot(gui, slotNum);

                // Failed to clear the slot
                if (slotTmp.hasStack())
                {
                    clearedAll = false;
                }
            }
        }

        return clearedAll;
    }

    private static boolean tryMoveItemsToCraftingGridSlots(RecipePattern recipe,
                                                           Slot slot,
                                                           HandledScreen<? extends ScreenHandler> gui,
                                                           boolean fillStacks)
    {
        ScreenHandler container = gui.getScreenHandler();
        int numSlots = container.slots.size();
        SlotRange range = CraftingHandler.getCraftingGridSlots(gui, slot);

        // Check that the slot range is valid and that the recipe can fit into this type of crafting grid
        if (range != null && range.getLast() < numSlots && recipe.getRecipeLength() <= range.getSlotCount())
        {
            // Clear non-matching items from the grid first
            if (clearCraftingGridOfItems(recipe, gui, range, true) == false)
            {
                return false;
            }

            // This slot is used to check that we get items from a DIFFERENT inventory than where this slot is in
            Slot slotGridFirst = container.getSlot(range.getFirst());
            Map<ItemType, IntArrayList> ingredientSlots = ItemType.getSlotsPerItem(recipe.getRecipeItems());

            for (Map.Entry<ItemType, IntArrayList> entry : ingredientSlots.entrySet())
            {
                ItemStack ingredientReference = entry.getKey().stack();
                IntArrayList recipeSlots = entry.getValue();
                IntArrayList targetSlots = new IntArrayList();

                // Get the actual target slot numbers based on the grid's start and the relative positions inside the grid
                for (int s : recipeSlots)
                {
                    targetSlots.add(s + range.getFirst());
                }

                if (fillStacks)
                {
                    fillCraftingGrid(gui, slotGridFirst, ingredientReference, targetSlots);
                }
                else
                {
                    moveOneRecipeItemIntoCraftingGrid(gui, slotGridFirst, ingredientReference, targetSlots);
                }
            }
        }

        return false;
    }

    private static void fillCraftingGrid(HandledScreen<? extends ScreenHandler> gui,
                                         Slot slotGridFirst,
                                         ItemStack ingredientReference,
                                         IntArrayList targetSlots)
    {
        ScreenHandler container = gui.getScreenHandler();
        int slotNum;
        int slotReturn = -1;
        int sizeOrig;

        if (isStackEmpty(ingredientReference))
        {
            return;
        }

        while (true)
        {
            slotNum = getSlotNumberOfLargestMatchingStackFromDifferentInventory(container, slotGridFirst, ingredientReference);

            // Didn't find ingredient items
            if (slotNum < 0)
            {
                break;
            }

            if (slotReturn == -1)
            {
                slotReturn = slotNum;
            }

            // Pick up the ingredient stack from the found slot
            leftClickSlot(gui, slotNum);

            ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

            // Successfully picked up ingredient items
            if (areStacksEqual(ingredientReference, stackCursor))
            {
                sizeOrig = getStackSize(stackCursor);
                dragSplitItemsIntoSlots(gui, targetSlots);
                stackCursor = gui.getScreenHandler().getCursorStack();

                // Items left in cursor
                if (isStackEmpty(stackCursor) == false)
                {
                    // Didn't manage to move any items anymore
                    if (getStackSize(stackCursor) >= sizeOrig)
                    {
                        break;
                    }

                    // Collect all the remaining items into the first found slot, as long as possible
                    leftClickSlot(gui, slotReturn);

                    // All of them didn't fit into the first slot anymore, switch into the current source slot
                    if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
                    {
                        slotReturn = slotNum;
                        leftClickSlot(gui, slotReturn);
                    }
                }
            }
            // Failed to pick up the stack, break to avoid infinite loops
            // TODO: we could also "blacklist" this slot and try to continue...?
            else
            {
                break;
            }

            // Somehow items were left in the cursor, break here
            if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
            {
                break;
            }
        }

        // Return the rest of the items to the original slot
        if (slotNum >= 0 && isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            leftClickSlot(gui, slotNum);
        }
    }

    public static void rightClickCraftOneStack(HandledScreen<? extends ScreenHandler> gui)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

        if (slot == null || slot.hasStack() == false ||
            (isStackEmpty(stackCursor) == false) && areStacksEqual(slot.getStack(), stackCursor) == false)
        {
            return;
        }

        int sizeLast = 0;

        while (true)
        {
            rightClickSlot(gui, slot.id);
            stackCursor = gui.getScreenHandler().getCursorStack();

            // Failed to craft items, or the stack became full, or ran out of ingredients
            if (isStackEmpty(stackCursor) || getStackSize(stackCursor) <= sizeLast ||
                getStackSize(stackCursor) >= stackCursor.getMaxCount() ||
                areStacksEqual(slot.getStack(), stackCursor) == false)
            {
                break;
            }

            sizeLast = getStackSize(stackCursor);
        }
    }

    public static void craftEverythingPossibleWithCurrentRecipe(RecipePattern recipe,
                                                                HandledScreen<? extends ScreenHandler> gui)
    {
        Slot slot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (slot != null && isStackEmpty(recipe.getResult()) == false)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, slot);

            if (range != null)
            {
                // Clear all items from the grid first, to avoid unbalanced stacks
                if (clearCraftingGridOfItems(recipe, gui, range, false) == false)
                {
                    return;
                }

                tryMoveItemsToCraftingGridSlots(recipe, slot, gui, true);

                if (slot.hasStack())
                {
                    craftAsManyItemsAsPossible(recipe, slot, gui);
                }
            }
        }
    }

    public static void moveAllCraftingResultsToOtherInventory(RecipePattern recipe,
                                                              HandledScreen<? extends ScreenHandler> gui)
    {
        if (isStackEmpty(recipe.getResult()) == false)
        {
            Slot slot = null;
            ItemStack stackResult = recipe.getResult().copy();

            for (Slot slotTmp : gui.getScreenHandler().slots)
            {
                // This slot is likely in the player inventory, as there is another inventory above
                if (areStacksEqual(slotTmp.getStack(), stackResult) &&
                    inventoryExistsAbove(slotTmp, gui.getScreenHandler()))
                {
                    slot = slotTmp;
                    break;
                }
            }

            if (slot != null)
            {
                // Get a list of slots with matching items, which are in the same inventory
                // as the slot that is assumed to be in the player inventory.
                IntArrayList slots = getSlotNumbersOfMatchingStacks(gui.getScreenHandler(), slot, true, stackResult, false, false, false);

                for (int slotNum : slots)
                {
                    shiftClickSlot(gui, slotNum);
                }
            }
        }
    }

    public static void throwAllCraftingResultsToGround(RecipePattern recipe,
                                                       HandledScreen<? extends ScreenHandler> gui)
    {
        Slot slot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (slot != null && isStackEmpty(recipe.getResult()) == false)
        {
            dropStacks(gui, recipe.getResult(), slot, false);
        }
    }

    public static void throwAllNonRecipeItemsToGround(RecipePattern recipe,
                                                      HandledScreen<? extends ScreenHandler> gui)
    {
        Slot outputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (outputSlot != null && isStackEmpty(recipe.getResult()) == false)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, outputSlot);
            ItemStack[] recipeItems = recipe.getRecipeItems();
            final int invSlots = gui.getScreenHandler().slots.size();
            final int rangeSlots = Math.min(range.getSlotCount(), recipeItems.length);

            for (int i = 0, slotNum = range.getFirst(); i < rangeSlots && slotNum < invSlots; i++, slotNum++)
            {
                Slot slotTmp = gui.getScreenHandler().getSlot(slotNum);
                ItemStack stack = slotTmp.getStack();

                if (stack.isEmpty() == false && areStacksEqual(stack, recipeItems[i]) == false)
                {
                    dropAllMatchingStacks(gui, stack);
                }
            }
        }
    }

    public static void setCraftingGridContentsUsingSwaps(HandledScreen<? extends ScreenHandler> gui,
                                                         PlayerInventory inv,
                                                         RecipePattern recipe,
                                                         Slot outputSlot)
    {
        SlotRange range = CraftingHandler.getCraftingGridSlots(gui, outputSlot);

        if (range != null && isStackEmpty(recipe.getResult()) == false)
        {
            ItemStack[] recipeItems = recipe.getRecipeItems();
            final int invSlots = gui.getScreenHandler().slots.size();
            final int rangeSlots = Math.min(range.getSlotCount(), recipeItems.length);
            IntArrayList toRemove = new IntArrayList();
            boolean movedSomething = false;

            setInhibitCraftingOutputUpdate(true);

            for (int i = 0, slotNum = range.getFirst(); i < rangeSlots && slotNum < invSlots; i++, slotNum++)
            {
                Slot craftingTableSlot = gui.getScreenHandler().getSlot(slotNum);
                ItemStack recipeStack = recipeItems[i];
                ItemStack slotStack = craftingTableSlot.getStack();

                if (areStacksEqual(recipeStack, slotStack) == false)
                {
                    if (recipeStack.isEmpty())
                    {
                        toRemove.add(slotNum);
                    }
                    else
                    {
                        int index = getSlotNumberOfLargestMatchingStackFromDifferentInventory(gui.getScreenHandler(), craftingTableSlot, recipeStack);

                        if (index >= 0)
                        {
                            Slot ingredientSlot = gui.getScreenHandler().getSlot(index);

                            if (ingredientSlot.inventory instanceof PlayerInventory && ingredientSlot.getIndex() < 9)
                            {
                                // hotbar
                                clickSlot(gui, slotNum, ingredientSlot.getIndex(), SlotActionType.SWAP);
                            }
                            else
                            {
                                swapSlots(gui, slotNum, index);
                            }
                            movedSomething = true;
                        }
                    }
                }
            }

            movedSomething |= !toRemove.isEmpty();

            for (int slotNum : toRemove)
            {
                shiftClickSlot(gui, slotNum);

                if (isStackEmpty(gui.getScreenHandler().getSlot(slotNum).getStack()) == false)
                {
                    dropStack(gui, slotNum);
                }
            }

            setInhibitCraftingOutputUpdate(false);

            if (movedSomething)
            {
                updateCraftingOutputSlot(outputSlot);
            }
        }
    }

    private static int putSingleItemIntoSlots(HandledScreen<? extends ScreenHandler> gui,
                                              IntArrayList targetSlots,
                                              int startIndex)
    {
        ItemStack stackInCursor = gui.getScreenHandler().getCursorStack();

        if (isStackEmpty(stackInCursor))
        {
            return 0;
        }

        int numSlots = gui.getScreenHandler().slots.size();
        int numItems = getStackSize(stackInCursor);
        int loops = Math.min(numItems, targetSlots.size() - startIndex);
        int count = 0;

        for (int i = 0; i < loops; i++)
        {
            int slotNum = targetSlots.getInt(startIndex + i);

            if (slotNum >= numSlots)
            {
                break;
            }

            rightClickSlot(gui, slotNum);
            count++;
        }

        return count;
    }

    public static void moveOneSetOfItemsFromSlotToPlayerInventory(HandledScreen<? extends ScreenHandler> gui,
                                                                  Slot slot)
    {
        leftClickSlot(gui, slot.id);

        ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

        if (isStackEmpty(stackCursor) == false)
        {
            IntArrayList slots = getSlotNumbersOfMatchingStacks(gui.getScreenHandler(), slot, false, stackCursor, true, true, false);

            if (moveItemFromCursorToSlots(gui, slots) == false)
            {
                slots = getSlotNumbersOfEmptySlotsInPlayerInventory(gui.getScreenHandler(), false);
                moveItemFromCursorToSlots(gui, slots);
            }
        }
    }

    private static void moveOneRecipeItemIntoCraftingGrid(HandledScreen<? extends ScreenHandler> gui,
                                                          Slot slotGridFirst,
                                                          ItemStack ingredientReference,
                                                          IntArrayList targetSlots)
    {
        ScreenHandler container = gui.getScreenHandler();
        int index = 0;
        int slotNum = -1;
        int slotCount = targetSlots.size();

        while (index < slotCount)
        {
            slotNum = getSlotNumberOfSmallestStackFromDifferentInventory(container, slotGridFirst, ingredientReference, slotCount);

            // Didn't find ingredient items
            if (slotNum < 0)
            {
                break;
            }

            // Pick up the ingredient stack from the found slot
            leftClickSlot(gui, slotNum);

            // Successfully picked up ingredient items
            if (areStacksEqual(ingredientReference, gui.getScreenHandler().getCursorStack()))
            {
                int filled = putSingleItemIntoSlots(gui, targetSlots, index);
                index += filled;

                if (filled < 1)
                {
                    break;
                }
            }
            // Failed to pick up the stack, break to avoid infinite loops
            // TODO: we could also "blacklist" this slot and try to continue...?
            else
            {
                break;
            }
        }

        // Return the rest of the items to the original slot
        if (slotNum >= 0 && isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            leftClickSlot(gui, slotNum);
        }
    }

    private static boolean moveItemFromCursorToSlots(HandledScreen<? extends ScreenHandler> gui,
                                                     IntArrayList slotNumbers)
    {
        for (int slotNum : slotNumbers)
        {
            leftClickSlot(gui, slotNum);

            if (isStackEmpty(gui.getScreenHandler().getCursorStack()))
            {
                return true;
            }
        }

        return false;
    }

    private static void moveItemsFromInventory(HandledScreen<? extends ScreenHandler> gui,
                                               int slotTo,
                                               Inventory invSrc,
                                               ItemStack stackTemplate,
                                               boolean fillStacks)
    {
        ScreenHandler container = gui.getScreenHandler();

        for (Slot slot : container.slots)
        {
            if (slot == null)
            {
                continue;
            }

            if (slot.inventory == invSrc && areStacksEqual(stackTemplate, slot.getStack()))
            {
                if (fillStacks)
                {
                    if (clickSlotsToMoveItems(gui, slot.id, slotTo) == false)
                    {
                        break;
                    }
                }
                else
                {
                    clickSlotsToMoveSingleItem(gui, slot.id, slotTo);
                    break;
                }
            }
        }
    }

    private static int getSlotNumberOfLargestMatchingStackFromDifferentInventory(ScreenHandler container,
                                                                                 Slot slotReference,
                                                                                 ItemStack stackReference)
    {
        int slotNum = -1;
        int largest = 0;

        for (Slot slot : container.slots)
        {
            if (areSlotsInSameInventory(slot, slotReference) == false && slot.hasStack() &&
                areStacksEqual(stackReference, slot.getStack()))
            {
                int stackSize = getStackSize(slot.getStack());

                if (stackSize > largest)
                {
                    slotNum = slot.id;
                    largest = stackSize;
                }
            }
        }

        return slotNum;
    }

    /**
     * Returns the slot number of the slot that has the smallest stackSize that is still equal to or larger
     * than idealSize. The slot must also NOT be in the same inventory as slotReference.
     * If an adequately large stack is not found, then the largest one is selected.
     */
    private static int getSlotNumberOfSmallestStackFromDifferentInventory(ScreenHandler container,
                                                                          Slot slotReference,
                                                                          ItemStack stackReference,
                                                                          int idealSize)
    {
        int slotNumSmallest = -1;
        int slotNumLargest = -1;
        int smallest = Integer.MAX_VALUE;
        int largest = 0;

        for (Slot slot : container.slots)
        {
            if (areSlotsInSameInventory(slot, slotReference) == false && slot.hasStack() &&
                areStacksEqual(stackReference, slot.getStack()))
            {
                int stackSize = getStackSize(slot.getStack());

                if (stackSize < smallest && stackSize >= idealSize)
                {
                    slotNumSmallest = slot.id;
                    smallest = stackSize;
                }

                if (stackSize > largest)
                {
                    slotNumLargest = slot.id;
                    largest = stackSize;
                }
            }
        }

        return slotNumSmallest != -1 ? slotNumSmallest : slotNumLargest;
    }

    /**
     * Return the slot numbers of slots that have items identical to stackReference.
     * If preferPartial is true, then stacks with a stackSize less that getMaxStackSize() are
     * at the beginning of the list (not ordered though) and full stacks are at the end, otherwise the reverse is true.
     * @param container
     * @param slotReference
     * @param sameInventory if true, then the returned slots are from the same inventory, if false, then from a different inventory
     * @param stackReference
     * @param preferPartial
     * @param treatHotbarAsDifferent
     * @param reverse if true, returns the slots starting from the end of the inventory
     * @return
     */
    @SuppressWarnings("SameParameterValue")
    private static IntArrayList getSlotNumbersOfMatchingStacks(ScreenHandler container,
                                                               Slot slotReference,
                                                               boolean sameInventory,
                                                               ItemStack stackReference,
                                                               boolean preferPartial,
                                                               boolean treatHotbarAsDifferent,
                                                               boolean reverse)
    {
        IntArrayList slots = new IntArrayList(64);
        final int maxSlot = container.slots.size() - 1;
        final int increment = reverse ? -1 : 1;

        for (int i = reverse ? maxSlot : 0; i >= 0 && i <= maxSlot; i += increment)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.hasStack() &&
                areSlotsInSameInventory(slot, slotReference, treatHotbarAsDifferent) == sameInventory &&
                areStacksEqual(slot.getStack(), stackReference))
            {
                if ((getStackSize(slot.getStack()) < stackReference.getMaxCount()) == preferPartial)
                {
                    slots.add(0, slot.id);
                }
                else
                {
                    slots.add(slot.id);
                }
            }
        }

        return slots;
    }

    @SuppressWarnings("SameParameterValue")
    private static IntArrayList getSlotNumbersOfMatchingStacks(ScreenHandler container,
                                                               ItemStack stackReference,
                                                               boolean preferPartial)
    {
        IntArrayList slots = new IntArrayList(64);
        final int maxSlot = container.slots.size() - 1;

        for (int i = 0; i <= maxSlot; ++i)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.hasStack() && areStacksEqual(slot.getStack(), stackReference))
            {
                if ((getStackSize(slot.getStack()) < stackReference.getMaxCount()) == preferPartial)
                {
                    slots.add(0, slot.id);
                }
                else
                {
                    slots.add(slot.id);
                }
            }
        }

        return slots;
    }

    public static int getPlayerInventoryIndexWithItem(ItemStack stackReference, PlayerInventory inv)
    {
        final int size = inv.main.size();

        for (int index = 0; index < size; ++index)
        {
            ItemStack stack = inv.main.get(index);

            if (areStacksEqual(stack, stackReference))
            {
                return index;
            }
        }

        return -1;
    }

    @SuppressWarnings("SameParameterValue")
    private static IntArrayList getSlotNumbersOfEmptySlots(ScreenHandler container,
                                                           Slot slotReference,
                                                           boolean sameInventory,
                                                           boolean treatHotbarAsDifferent,
                                                           boolean reverse)
    {
        IntArrayList slots = new IntArrayList(64);
        final int maxSlot = container.slots.size() - 1;
        final int increment = reverse ? -1 : 1;

        for (int i = reverse ? maxSlot : 0; i >= 0 && i <= maxSlot; i += increment)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.hasStack() == false &&
                areSlotsInSameInventory(slot, slotReference, treatHotbarAsDifferent) == sameInventory)
            {
                slots.add(slot.id);
            }
        }

        return slots;
    }

    @SuppressWarnings("SameParameterValue")
    private static IntArrayList getSlotNumbersOfEmptySlotsInPlayerInventory(ScreenHandler container,
                                                                            boolean reverse)
    {
        IntArrayList slots = new IntArrayList(64);
        final int maxSlot = container.slots.size() - 1;
        final int increment = reverse ? -1 : 1;

        for (int i = reverse ? maxSlot : 0; i >= 0 && i <= maxSlot; i += increment)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && (slot.inventory instanceof PlayerInventory) && slot.hasStack() == false)
            {
                slots.add(slot.id);
            }
        }

        return slots;
    }

    public static boolean areStacksEqual(ItemStack stack1, ItemStack stack2)
    {
        return ItemStack.areItemsAndComponentsEqual(stack1, stack2);
    }

    private static boolean areSlotsInSameInventory(Slot slot1, Slot slot2)
    {
        return areSlotsInSameInventory(slot1, slot2, false);
    }

    private static boolean areSlotsInSameInventory(Slot slot1, Slot slot2, boolean treatHotbarAsDifferent)
    {
        if (slot1.inventory == slot2.inventory)
        {
            if (treatHotbarAsDifferent && slot1.inventory instanceof PlayerInventory)
            {
                int index1 = AccessorUtils.getSlotIndex(slot1);
                int index2 = AccessorUtils.getSlotIndex(slot2);
                // Don't ever treat the offhand slot as a different inventory
                return index1 == 40 || index2 == 40 || (index1 < 9) == (index2 < 9);
            }

            return true;
        }

        return false;
    }

    private static ItemStack[] getOriginalStacks(ScreenHandler container)
    {
        ItemStack[] originalStacks = new ItemStack[container.slots.size()];

        for (int i = 0; i < originalStacks.length; i++)
        {
            originalStacks[i] = container.slots.get(i).getStack().copy();
        }

        return originalStacks;
    }

    private static void restoreOriginalStacks(ScreenHandler container, ItemStack[] originalStacks)
    {
        for (int i = 0; i < originalStacks.length; i++)
        {
            ItemStack stackSlot = container.getSlot(i).getStack();

            if (areStacksEqual(stackSlot, originalStacks[i]) == false ||
                (isStackEmpty(stackSlot) == false && getStackSize(stackSlot) != getStackSize(originalStacks[i])))
            {
                container.getSlot(i).setStackNoCallbacks(originalStacks[i]);
            }
        }
    }

    private static int getTargetSlot(ScreenHandler container, ItemStack[] originalStacks)
    {
        List<Slot> slots = container.slots;

        for (int i = 0; i < originalStacks.length; i++)
        {
            ItemStack stackOrig = originalStacks[i];
            ItemStack stackNew = slots.get(i).getStack();

            if ((isStackEmpty(stackOrig) && isStackEmpty(stackNew) == false) ||
               (isStackEmpty(stackOrig) == false && isStackEmpty(stackNew) == false &&
               getStackSize(stackNew) == (getStackSize(stackOrig) + 1)))
            {
                return i;
            }
        }

        return -1;
    }

    /*
    private void clickSlotsToMoveItems(Slot slot, ContainerScreen<? extends Container> gui, boolean matchingOnly, boolean toOtherInventory)
    {
        for (Slot slotTmp : gui.getContainer().slots)
        {
            if (slotTmp.id != slot.id && areSlotsInSameInventory(slotTmp, slot) == toOtherInventory &&
                slotTmp.hasStack() && (matchingOnly == false || areStacksEqual(slot.getStack(), slotTmp.getStack())))
            {
                this.clickSlotsToMoveItemsFromSlot(slotTmp, gui, toOtherInventory);
                return;
            }
        }

        // Move the hovered-over slot's stack last
        if (toOtherInventory)
        {
            this.clickSlotsToMoveItemsFromSlot(slot, gui, toOtherInventory);
        }
    }
    */

    private static void clickSlotsToMoveItemsFromSlot(Slot slotFrom,
                                                      HandledScreen<? extends ScreenHandler> gui,
                                                      boolean toOtherInventory)
    {
        // Left click to pick up the found source stack
        leftClickSlot(gui, slotFrom.id);

        if (isStackEmpty(gui.getScreenHandler().getCursorStack()))
        {
            return;
        }

        for (Slot slotDst : gui.getScreenHandler().slots)
        {
            ItemStack stackDst = slotDst.getStack();

            if (areSlotsInSameInventory(slotDst, slotFrom) != toOtherInventory &&
                (isStackEmpty(stackDst) || areStacksEqual(stackDst, gui.getScreenHandler().getCursorStack())))
            {
                // Left click to (try and) place items to the slot
                leftClickSlot(gui, slotDst.id);
            }

            if (isStackEmpty(gui.getScreenHandler().getCursorStack()))
            {
                return;
            }
        }

        // Couldn't fit the entire stack to the target inventory, return the rest of the items
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            leftClickSlot(gui, slotFrom.id);
        }
    }

    private static boolean clickSlotsToMoveSingleItem(HandledScreen<? extends ScreenHandler> gui,
                                                      int slotFrom,
                                                      int slotTo)
    {
        //System.out.println("clickSlotsToMoveSingleItem(from: " + slotFrom + ", to: " + slotTo + ")");
        ItemStack stack = gui.getScreenHandler().slots.get(slotFrom).getStack();

        if (isStackEmpty(stack))
        {
            return false;
        }

        // Click on the from-slot to take items to the cursor - if there is more than one item in the from-slot,
        // right click on it, otherwise left click.
        if (getStackSize(stack) > 1)
        {
            rightClickSlot(gui, slotFrom);
        }
        else
        {
            leftClickSlot(gui, slotFrom);
        }

        // Right click on the target slot to put one item to it
        rightClickSlot(gui, slotTo);

        // If there are items left in the cursor, then return them back to the original slot
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            // Left click again on the from-slot to return the rest of the items to it
            leftClickSlot(gui, slotFrom);
        }

        return true;
    }

    private static boolean clickSlotsToMoveSingleItemByShiftClick(HandledScreen<? extends ScreenHandler> gui,
                                                                  int slotFrom)
    {
        Slot slot = gui.getScreenHandler().slots.get(slotFrom);
        ItemStack stack = slot.getStack();

        if (isStackEmpty(stack))
        {
            return false;
        }

        if (getStackSize(stack) > 1)
        {
            // Left click on the from-slot to take all the items to the cursor
            leftClickSlot(gui, slotFrom);

            // Still items left in the slot, put the stack back and abort
            if (slot.hasStack())
            {
                leftClickSlot(gui, slotFrom);
                return false;
            }
            else
            {
                // Right click one item back to the slot
                rightClickSlot(gui, slotFrom);
            }
        }

        // ... and then shift-click on the slot
        shiftClickSlot(gui, slotFrom);

        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            // ... and then return the rest of the items
            leftClickSlot(gui, slotFrom);
        }

        return true;
    }

    /**
     * Try move items from slotFrom to slotTo
     * @return true if at least some items were moved
     */
    private static boolean clickSlotsToMoveItems(HandledScreen<? extends ScreenHandler> gui,
                                                 int slotFrom,
                                                 int slotTo)
    {
        //System.out.println("clickSlotsToMoveItems(from: " + slotFrom + ", to: " + slotTo + ")");

        // Left click to take items
        leftClickSlot(gui, slotFrom);

        // Couldn't take the items, bail out now
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()))
        {
            return false;
        }

        boolean ret = true;
        int size = getStackSize(gui.getScreenHandler().getCursorStack());

        // Left click on the target slot to put the items to it
        leftClickSlot(gui, slotTo);

        // If there are items left in the cursor, then return them back to the original slot
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            ret = getStackSize(gui.getScreenHandler().getCursorStack()) != size;

            // Left click again on the from-slot to return the rest of the items to it
            leftClickSlot(gui, slotFrom);
        }

        return ret;
    }

    public static void dropStacksUntilEmpty(HandledScreen<? extends ScreenHandler> gui,
                                            int slotNum)
    {
        if (slotNum >= 0 && slotNum < gui.getScreenHandler().slots.size())
        {
            Slot slot = gui.getScreenHandler().getSlot(slotNum);
            int failsafe = 64;

            while (failsafe-- > 0 && slot.hasStack())
            {
                dropStack(gui, slotNum);
            }
        }
    }

    public static void dropStacksWhileHasItem(HandledScreen<? extends ScreenHandler> gui,
                                              int slotNum,
                                              ItemStack stackReference)
    {
        if (slotNum >= 0 && slotNum < gui.getScreenHandler().slots.size())
        {
            Slot slot = gui.getScreenHandler().getSlot(slotNum);
            int failsafe = 256;

            while (failsafe-- > 0 && areStacksEqual(slot.getStack(), stackReference))
            {
                dropStack(gui, slotNum);
            }
        }
    }

    private static boolean shiftClickSlotWithCheck(HandledScreen<? extends ScreenHandler> gui,
                                                   int slotNum)
    {
        Slot slot = gui.getScreenHandler().getSlot(slotNum);

        if (slot == null || slot.hasStack() == false)
        {
            return false;
        }

        int sizeOrig = getStackSize(slot.getStack());
        shiftClickSlot(gui, slotNum);

        return slot.hasStack() == false || getStackSize(slot.getStack()) != sizeOrig;
    }

    public static boolean tryMoveItemsVertically(HandledScreen<? extends ScreenHandler> gui,
                                                 Slot slot,
                                                 boolean moveUp,
                                                 MoveAmount amount)
    {
        // We require an empty cursor
        if (slot == null || isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            return false;
        }

        IntArrayList slots = getVerticallyFurthestSuitableSlotsForStackInSlot(gui.getScreenHandler(), slot, moveUp);

        if (slots.isEmpty())
        {
            return false;
        }

        if (amount == MoveAmount.FULL_STACKS)
        {
            moveStackToSlots(gui, slot, slots, false);
        }
        else if (amount == MoveAmount.MOVE_ONE)
        {
            moveOneItemToFirstValidSlot(gui, slot, slots);
        }
        else if (amount == MoveAmount.LEAVE_ONE)
        {
            moveStackToSlots(gui, slot, slots, true);
        }
        else if (amount == MoveAmount.ALL_MATCHING)
        {
            moveMatchingStacksToSlots(gui, slot, moveUp);
        }

        return true;
    }

    private static void moveMatchingStacksToSlots(HandledScreen<? extends ScreenHandler> gui,
                                                  Slot slot,
                                                  boolean moveUp)
    {
        IntArrayList matchingSlots = getSlotNumbersOfMatchingStacks(gui.getScreenHandler(), slot, true, slot.getStack(), true, true, false);
        IntArrayList targetSlots = getSlotNumbersOfEmptySlots(gui.getScreenHandler(), slot, false, true, false);
        targetSlots.addAll(getSlotNumbersOfEmptySlots(gui.getScreenHandler(), slot, true, true, false));
        targetSlots.addAll(matchingSlots);

        matchingSlots.sort(new SlotVerticalSorterSlotNumbers(gui.getScreenHandler(), !moveUp));
        targetSlots.sort(new SlotVerticalSorterSlotNumbers(gui.getScreenHandler(), moveUp));

        for (int matchingSlot : matchingSlots)
        {
            int srcSlotNum = matchingSlot;
            Slot srcSlot = gui.getScreenHandler().getSlot(srcSlotNum);
            Slot lastSlot = moveStackToSlots(gui, srcSlot, targetSlots, false);

            if (lastSlot == null || (lastSlot.id == srcSlot.id || (lastSlot.y > srcSlot.y) == moveUp))
            {
                return;
            }
        }
    }

    private static Slot moveStackToSlots(HandledScreen<? extends ScreenHandler> gui,
                                         Slot slotFrom,
                                         IntArrayList slotsTo,
                                         boolean leaveOne)
    {
        Slot lastSlot = null;

        // Empty slot, nothing to do
        if (slotFrom.hasStack() == false)
        {
            return null;
        }

        // Pick up the stack
        leftClickSlot(gui, slotFrom.id);

        if (leaveOne)
        {
            rightClickSlot(gui, slotFrom.id);
        }

        for (int slotNum : slotsTo)
        {
            // Empty cursor, all done here
            if (isStackEmpty(gui.getScreenHandler().getCursorStack()))
            {
                break;
            }

            Slot dstSlot = gui.getScreenHandler().getSlot(slotNum);

            if (dstSlot.canInsert(gui.getScreenHandler().getCursorStack()) &&
                (dstSlot.hasStack() == false || areStacksEqual(dstSlot.getStack(), gui.getScreenHandler().getCursorStack())))
            {
                leftClickSlot(gui, slotNum);
                lastSlot = dstSlot;
            }
        }

        // Return the rest of the items, if any
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            leftClickSlot(gui, slotFrom.id);
        }

        return lastSlot;
    }

    private static void moveOneItemToFirstValidSlot(HandledScreen<? extends ScreenHandler> gui,
                                                    Slot slotFrom,
                                                    IntArrayList slotsTo)
    {
        // Pick up half of the the stack
        rightClickSlot(gui, slotFrom.id);

        if (isStackEmpty(gui.getScreenHandler().getCursorStack()))
        {
            return;
        }

        int sizeOrig = getStackSize(gui.getScreenHandler().getCursorStack());

        for (int slotNum : slotsTo)
        {
            rightClickSlot(gui, slotNum);
            ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

            if (isStackEmpty(stackCursor) || getStackSize(stackCursor) != sizeOrig)
            {
                break;
            }
        }

        // Return the rest of the items, if any
        if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
        {
            leftClickSlot(gui, slotFrom.id);
        }
    }

    private static IntArrayList getVerticallyFurthestSuitableSlotsForStackInSlot(ScreenHandler container,
                                                                                  Slot slotIn,
                                                                                  boolean above)
    {
        if (slotIn == null || slotIn.hasStack() == false)
        {
            return IntArrayList.of();
        }

        IntArrayList slotNumbers = new IntArrayList();
        ItemStack stackSlot = slotIn.getStack();

        for (Slot slotTmp : container.slots)
        {
            if (slotTmp.id != slotIn.id && slotTmp.y != slotIn.y)
            {
                if (above == slotTmp.y < slotIn.y)
                {
                    ItemStack stackTmp = slotTmp.getStack();

                    if ((isStackEmpty(stackTmp) && slotTmp.canInsert(stackSlot)) ||
                        (areStacksEqual(stackTmp, stackSlot)) && slotTmp.getMaxItemCount(stackTmp) > getStackSize(stackTmp))
                    {
                        slotNumbers.add(slotTmp.id);
                    }
                }
            }
        }

        slotNumbers.sort(new SlotVerticalSorterSlotNumbers(container, above));

        return slotNumbers;
    }

    public static void tryClearCursor(HandledScreen<? extends ScreenHandler> gui)
    {
        ItemStack stackCursor = gui.getScreenHandler().getCursorStack();

        if (isStackEmpty(stackCursor) == false)
        {
            IntArrayList emptySlots = getSlotNumbersOfEmptySlotsInPlayerInventory(gui.getScreenHandler(), false);

            if (emptySlots.isEmpty() == false)
            {
                leftClickSlot(gui, emptySlots.getInt(0));
            }
            else
            {
                IntArrayList matchingSlots = getSlotNumbersOfMatchingStacks(gui.getScreenHandler(), stackCursor, true);

                if (matchingSlots.isEmpty() == false)
                {
                    for (int slotNum : matchingSlots)
                    {
                        Slot slot = gui.getScreenHandler().getSlot(slotNum);
                        ItemStack stackSlot = slot.getStack();

                        if (slot == null || areStacksEqual(stackSlot, stackCursor) == false ||
                            getStackSize(stackSlot) >= stackCursor.getMaxCount())
                        {
                            break;
                        }

                        if (slot.inventory instanceof PlayerInventory)
                        {
                            leftClickSlot(gui, slotNum);
                            stackCursor = gui.getScreenHandler().getCursorStack();
                        }
                    }
                }
            }

            if (isStackEmpty(gui.getScreenHandler().getCursorStack()) == false)
            {
                dropItemsFromCursor(gui);
            }
        }
    }

    public static void resetLastSlotNumber()
    {
        slotNumberLast = -1;
    }

    public static MoveAction getActiveMoveAction()
    {
        return activeMoveAction;
    }

    public static void sortInventory(HandledScreen<?> gui)
    {
        Pair<Integer, Integer> range = new IntIntMutablePair(Integer.MAX_VALUE, 0);
        Slot focusedSlot = AccessorUtils.getSlotUnderMouse(gui);
        MinecraftClient mc = GameWrap.getClient();
        boolean shulkerBoxFix;

        if (focusedSlot == null || focusedSlot.hasStack() == false)
        {
            return;
        }

        //System.out.printf("sort - focusedSlot[%d]: %s\n", focusedSlot.id, focusedSlot.hasStack() ? focusedSlot.getStack().getName().getString() : "<EMPTY>");
        ScreenHandler container = gui.getScreenHandler();
        int limit = container.slots.size();
        int focusedIndex = -1;

        if (gui instanceof CreativeInventoryScreen creative && !creative.isInventoryTabSelected())
        {
            return;
        }
        if (gui instanceof InventoryScreen && (focusedSlot.id < 9 || focusedSlot.id > 44))
        {
            return;
        }

        // Do not try to sort shulkers inside a shulker
        shulkerBoxFix = gui instanceof ShulkerBoxScreen && focusedSlot.id < 27;

        for (int i = 0; i < limit; i++)
        {
            Slot slot = container.slots.get(i);

            //System.out.printf("sort - slot[%d]: %s\n", i, slot.hasStack() ? slot.getStack().getName().getString() : "<EMPTY>");
            if (slot == focusedSlot)
            {
                focusedIndex = i;
            }
            if (slot.inventory == focusedSlot.inventory)
            {
                if (i < range.first())
                {
                    range.first(i);
                }
                if (i >= range.second())
                {
                    range.second(i + 1);
                }
            }
        }

        if (focusedIndex == -1)
        {
            return;
        }

        if (focusedSlot.inventory instanceof PlayerInventory)
        {
            if (range.left() == 5 && range.right() == 46)
            {
                // Creative, PlayerScreenHandler
                if (focusedIndex >= 9 && focusedIndex < 36)
                {
                    range.left(9).right(36);
                }
                else if (focusedIndex >= 36 && focusedIndex < 45)
                {
                    range.left(36).right(45);
                }
            }
            else if (range.right() - range.left() == 36)
            {
                // Normal containers
                if (focusedIndex < range.left() + 27)
                {
                    range.right(range.left() + 27);
                }
                else
                {
                    range.left(range.right() - 9);
                }
            }
        }

        // try to find usable hotbar slot
        int hotbarSlot = 8;
        if ( shulkerBoxFix )
        {
            var playerInventory = MinecraftClient.getInstance().player.getInventory();
            while ( hotbarSlot >= 0 )
            {
                int slot_ix = container.getSlotIndex(playerInventory, hotbarSlot).orElse(-1);
                if ( slot_ix != -1 && !isShulkerBox(container.getSlot(slot_ix).getStack()) )
                {
                    break;
                }

                --hotbarSlot;
            }

            if ( hotbarSlot < 0 )
            {
                ItemScroller.logger.warn("sortInventory(): no usable hotbar slot to sort shulkerbox");
                return;
            }
        }

        final int swapSlot = hotbarSlot;

        //System.out.printf("Sorting [%d, %d] (first, second)\n", range.first(), range.second());
        //System.out.printf("Sorting [%d, %d] (left, right)\n", range.left(), range.right());
        tryClearCursor(gui);
        tryMergeItems(gui, range.left(), range.right() - 1);

        if (Configs.Generic.SORT_ASSUME_EMPTY_BOX_STACKS.getBooleanValue())
        {
            ClientStatusC2SPacket packet = new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS);

            mc.getNetworkHandler().sendPacket(packet);
            selectedSlotUpdateTask = () -> trySort(gui, range.first(), range.second(), shulkerBoxFix, swapSlot);
        }
        else
        {
            trySort(gui, range.first(), range.second(), shulkerBoxFix, swapSlot);
        }
    }

    private static void trySort(HandledScreen<?> gui, int start, int end, boolean shulkerBoxFix, int swapSlot)
    {
        try
        {
            quickSort(gui, start, end, shulkerBoxFix, swapSlot);
        }
        catch (Exception err)
        {
            ItemScroller.logger.error("trySort(): failed to sort items", err);
        }
    }

    private static void quickSort(HandledScreen<?> gui, int start, int end, boolean shulkerBoxFix, int swapSlot)
    {
        var ct = end - start;
        var handler = gui.getScreenHandler();

        // make snapshot of contents; give each item a temporary ID.
        // this ID also happens to be its slot index, relative to `start`.
        var snapshot = new ArrayList<>
        (
                IntStream.range(0, end - start)
                    .mapToObj(ix -> Pair.of(ix, handler.getSlot(start + ix).getStack().copy()))
                    .filter(pair -> !(shulkerBoxFix && isShulkerBox(pair.value())))
                    .toList()
        );
        ct = snapshot.size();

        // because the array might have unsortable holes, build an index from array index to slot index
        int[] slotindex_by_arrayindex = snapshot.stream().mapToInt(pair -> start + pair.key()).toArray();

        // sort pairs
        List<Pair<Integer, ItemStack>> sorted_pairs =
        (
            snapshot.stream()
                .sorted(
                    (left, right) ->
                        compareStacks(left.value(), right.value())
                    )
                .toList()
        );

        ItemScroller.logger.debug(String.format
        (
            "======\nsort\n%s\n\n",
            IntStream.range(0, ct)
                .mapToObj(
                    ix -> String.format(
                        "%2d: %2d/%-20s  %2d/%-20s",
                        ix,
                        snapshot.get(ix).key(), snapshot.get(ix).value().getName().getString(),
                        sorted_pairs.get(ix).key(),sorted_pairs.get(ix).value().getName().getString()
                    )
                )
                .collect(Collectors.joining("\n"))
        ));

        // build index of an item's final position by its fake ID
        Map<Integer, Integer> finalpos_by_id =
        (
            IntStream.range(0, ct).boxed()
                .collect(Collectors.toMap(
                    ix -> sorted_pairs.get(ix).key(),
                    ix -> ix
                ))
        );

        // sort
        int limit = 0, max_limit = 200;
        Pair<Integer,ItemStack> dst, hold = null;

        for (int src_ix = 0; src_ix < ct; ++src_ix)
        {
            // check if item is in correct position
            Pair<Integer,ItemStack> src = snapshot.get(src_ix);
            int src_id = src.key();
            int dst_ix = finalpos_by_id.get(src_id);

            dst = snapshot.get(dst_ix);

            if (src_ix == dst_ix)
            {
                ItemScroller.logger.debug("quickSort(): {} ok", src_ix);
                continue;
            }

            // pick up and hold "src"
            snapshot.set(src_ix, hold);
            hold = src;
            ItemScroller.logger.debug("quickSort(): pick up {}; holding {}", src_ix, hold);
            clickSlot(gui, slotindex_by_arrayindex[src_ix], swapSlot, SlotActionType.SWAP);

            // continually place the held item into its correct place, following the chain to its end
            // todo: we could skip swapping empty slots, but for some reason, this is not reliable. it seems to swap
            //       in an item from the player's hotbar into the container.
            for (limit = 0; limit < max_limit; ++limit)
            {
                snapshot.set(dst_ix, hold);
                hold = dst;
                clickSlot(gui, slotindex_by_arrayindex[dst_ix], swapSlot, SlotActionType.SWAP);

                ItemScroller.logger.debug("quickSort(): ... swap {} {}; holding {}", dst_ix, dst != null ? dst.value() : "null", hold);
                if (hold == null)
                {
                    break;
                }

                dst_ix = finalpos_by_id.get(hold.key());
                dst = snapshot.get(dst_ix);
            }

            if (limit == max_limit)
            {
                ItemScroller.logger.warn("quickSort(): took too long to follow swap chain ??");
            }

        }
        if (hold != null)
        {
            ItemScroller.logger.warn("quickSort(): sorting complete, but still holding {} ??", hold);
        }
    }

    private static int compareStacks(ItemStack stack1, ItemStack stack2)
    {
        MinecraftClient mc = GameWrap.getClient();

        stack1 = stack1 != null ? stack1 : ItemStack.EMPTY;
        stack2 = stack2 != null ? stack2 : ItemStack.EMPTY;

        // boxes towards the end of the list
        boolean stack1IsBox = isShulkerBox(stack1);
        boolean stack2IsBox = isShulkerBox(stack2);

        if (Configs.Generic.SORT_SHULKER_BOXES_AT_END.getBooleanValue() && stack1IsBox != stack2IsBox)
        {
            return Boolean.compare(stack1IsBox, stack2IsBox);
        }

        // bundles towards the end of the list
        boolean stack1IsBundle = isBundle(stack1);
        boolean stack2IsBundle = isBundle(stack2);

        if (Configs.Generic.SORT_BUNDLES_AT_END.getBooleanValue() && stack1IsBundle != stack2IsBundle)
        {
            return Boolean.compare(stack1IsBundle, stack2IsBundle);
        }

        // empty slots last
        boolean stack1IsEmpty = stack1.isEmpty();
        boolean stack2IsEmpty = stack2.isEmpty();

        if (stack1IsEmpty != stack2IsEmpty)
        {
            return Boolean.compare(stack1IsEmpty, stack2IsEmpty);
        }

        if (stack1IsEmpty)
        {
            // both stacks are empty
            return 0;
        }

        // order items according to user-defined top/bottom priority
        // a priority of -1 means that no priority was specified
        int priority1 = getCustomPriority(stack1);
        int priority2 = getCustomPriority(stack2);
        boolean stack1HasUnspecifiedPriority = priority1 == -1;
        boolean stack2HasUnspecifiedPriority = priority2 == -1;

        if (stack1HasUnspecifiedPriority != stack2HasUnspecifiedPriority)
        {
            return Boolean.compare(stack1HasUnspecifiedPriority, stack2HasUnspecifiedPriority);
        }

        if (priority1 != -1)
        {
            return Integer.compare(priority1, priority2);
        }

        // sort by shulker box contents
        if (stack1IsBox && stack2IsBox)
        {
            List<ItemStack> contents1 = stack1.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).streamNonEmpty().toList();
            List<ItemStack> contents2 = stack2.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).streamNonEmpty().toList();
            int flip = (Configs.Generic.SORT_SHULKER_BOXES_INVERTED.getBooleanValue() ? -1 : 1);

            return Integer.compare(contents1.size(), contents2.size()) * flip;
        }

        // sort by bundle contents
        if (stack1IsBundle && stack2IsBundle)
        {
            BundleContentsComponent bundle1 = stack1.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            BundleContentsComponent bundle2 = stack2.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            int flip = (Configs.Generic.SORT_BUNDLES_INVERTED.getBooleanValue() ? -1 : 1);
            Fraction occupancy1 = bundle1.getOccupancy();
            Fraction occupancy2 = bundle2.getOccupancy();

            return occupancy1.compareTo(occupancy2) * flip;
        }

        SortingMethod method = (SortingMethod) Configs.Generic.SORT_METHOD_DEFAULT.getOptionListValue();

        if (method.equals(SortingMethod.CATEGORY_NAME) ||
            method.equals(SortingMethod.CATEGORY_COUNT) ||
            method.equals(SortingMethod.CATEGORY_RARITY) ||
            method.equals(SortingMethod.CATEGORY_RAWID) &&
            mc.world != null)
        {
            // Sort by category
            if (displayContext == null)
            {
                displayContext = SortingCategory.INSTANCE.buildDisplayContext(mc);
                // This isn't used here, but it is required to build the list of items,
                // as if we are opening the Creative Inventory Screen.
            }

            SortingCategory.Entry cat1 = SortingCategory.INSTANCE.fromItemStack(stack1);
            SortingCategory.Entry cat2 = SortingCategory.INSTANCE.fromItemStack(stack2);

            if (!cat1.getStringValue().equals(cat2.getStringValue()))
            {
                int index1 = Configs.Generic.SORT_CATEGORY_ORDER.getEntryIndex(cat1);
                int index2 = Configs.Generic.SORT_CATEGORY_ORDER.getEntryIndex(cat2);
                boolean stack1UnspecifiedCategoryPriority = index1 == -1;
                boolean stack2UnspecifiedCategoryPriority = index2 == -1;

                if ( stack1UnspecifiedCategoryPriority != stack2UnspecifiedCategoryPriority)
                {
                    return Boolean.compare(stack1UnspecifiedCategoryPriority, stack2UnspecifiedCategoryPriority);
                }

                return Integer.compare(index1, index2);
            }
        }

        if (stack1.getItem() != stack2.getItem())
        {
            if (method.equals(SortingMethod.CATEGORY_NAME) || method.equals(SortingMethod.ITEM_NAME))
            {
                // Sort by Item Name
                return stack1.getName().getString().compareTo(stack2.getName().getString());
            }
            else if (method.equals(SortingMethod.CATEGORY_COUNT) || method.equals(SortingMethod.ITEM_COUNT))
            {
                // Sort by Item Count
                int result = Integer.compare(stack2.getCount(), stack1.getCount());
                if ( result != 0 )
                {
                    return result;
                }

                return Integer.compare(Registries.ITEM.getRawId(stack1.getItem()), Registries.ITEM.getRawId(stack2.getItem()));
            }
            else if (method.equals(SortingMethod.CATEGORY_RARITY) || method.equals(SortingMethod.ITEM_RARITY))
            {
                // Sort by Item Rarity
                int result = stack1.getRarity().compareTo(stack2.getRarity());
                if ( result != 0 )
                {
                    return result;
                }

                return Integer.compare(Registries.ITEM.getRawId(stack1.getItem()), Registries.ITEM.getRawId(stack2.getItem()));
            }
            else
            {
                // Sort by Item RawID
                return Integer.compare(Registries.ITEM.getRawId(stack1.getItem()), Registries.ITEM.getRawId(stack2.getItem()));
            }
        }
        if (areStacksEqual(stack1, stack2) == false)
        {
            // Sort's Data Components by Hash Code
            return Integer.compare(stack1.getComponents().hashCode(), stack2.getComponents().hashCode());
        }

        return Integer.compare(stack2.getCount(), stack1.getCount());
    }

    private static int getCustomPriority(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            // No priority for empty stacks
            return -1;
        }

        // Get item ID and name to check against custom priority lists
        String itemID = Registries.ITEM.getId(stack.getItem()).toString();
        String itemName = stack.getName().getString();

        if (itemID.equals(itemName))
        {
            itemName = null;
        }

        // Top priority check
        int idTopPriority = topSortingPriorityList.indexOf(itemID);
        int nameTopPriority = itemName != null ? topSortingPriorityList.indexOf(itemName) : -1;

        // Bottom priority check
        int idBottomPriority = bottomSortingPriorityList.indexOf(itemID);
        int nameBottomPriority = itemName != null ? bottomSortingPriorityList.indexOf(itemName) : -1;

        // Sort at the top: Prefer name priority if it exists
        if (nameTopPriority != -1)
        {
            return topSortingPriorityList.size() - nameTopPriority;
        }
        if (idTopPriority != -1)
        {
            return topSortingPriorityList.size() - idTopPriority;
        }

        // Sort at the bottom: Prefer name priority if it exists
        if (nameBottomPriority != -1)
        {
            return -bottomSortingPriorityList.size() - nameBottomPriority - 2;
        }
        if (idBottomPriority != -1)
        {
            return -bottomSortingPriorityList.size() - idBottomPriority - 2;
        }

        // Default: no specific priority found
        return -1;
    }

    public static boolean onPong(StatisticsS2CPacket packet)
    {
        if (selectedSlotUpdateTask != null)
        {
            selectedSlotUpdateTask.run();
            selectedSlotUpdateTask = null;
            return true;
        }
        return false;
    }

    private static boolean isShulkerBox(ItemStack stack)
    {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean isEmptyShulkerBox(ItemStack stack)
    {
        return isShulkerBox(stack) && stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).streamNonEmpty().findAny().isEmpty();
    }

    private static boolean isBundle(ItemStack stack)
    {
        return stack.isOf(Items.BUNDLE) || stack.getComponents().contains(DataComponentTypes.BUNDLE_CONTENTS);
    }

    private static boolean isEmptyBundle(ItemStack stack)
    {
        return isBundle(stack) && fi.dy.masa.malilib.util.InventoryUtils.bundleCountItems(stack) < 1;
    }

    public static int stackMaxSize(ItemStack stack, boolean assumeShulkerStacking)
    {
        if (stack.isEmpty())
        {
            return 64;
        }

        if (assumeShulkerStacking && Configs.Generic.SORT_ASSUME_EMPTY_BOX_STACKS.getBooleanValue())
        {
            if (isEmptyShulkerBox(stack))
            {
                return 64;
            }
        }

        return stack.getOrDefault(DataComponentTypes.MAX_STACK_SIZE, 1);
    }

    /**
     * @return are there still items left in the original slot?
     */
    private static boolean addStackTo(HandledScreen<? extends ScreenHandler> gui, Slot slot, Slot target)
    {
        if (slot == null || target == null)
        {
            return false;
        }

        ItemStack stack = slot.getStack();
        ItemStack targetStack = target.getStack();

        if (stack.isEmpty() || !ItemStack.areItemsEqual(stack, targetStack))
        {
            return !stack.isEmpty();
        }

        if (targetStack.isEmpty())
        {
            clickSlot(gui, slot, slot.id, 0, SlotActionType.PICKUP);
            clickSlot(gui, target, target.id, 0, SlotActionType.PICKUP);
            //System.out.printf("Moved stack from slot %d to slot %d\n", slot.id, target.id);
            //ItemScroller.printDebug("Moved stack from slot {} to slot {}", slot.id, target.id);
            return false;
        }

        int stackSize = stack.getCount();
        int targetSize = targetStack.getCount();
        assumeEmptyShulkerStacking = true;
        int maxSize = stackMaxSize(stack, true);
        //System.out.printf("Merging %s into %s, maxSize: %d\n", stack, targetStack, maxSize);
        //ItemScroller.printDebug("Merging {} into {}, maxSize: {}", stack, targetStack, maxSize);

        if (targetSize >= maxSize)
        {
            return true;
        }

        clickSlot(gui, slot, slot.id, 0, SlotActionType.PICKUP);
        clickSlot(gui, target, target.id, 0, SlotActionType.PICKUP);
        clickSlot(gui, slot, slot.id, 0, SlotActionType.PICKUP);
        assumeEmptyShulkerStacking = false;
        int amount = stackSize + targetSize - maxSize;

        return amount > 0;
    }

    private static void tryMergeItems(HandledScreen<?> gui, int left, int right)
    {
        Map<ItemType, Integer> nonFullStacks = new HashMap<>();

        for (int i = left; i <= right; i++)
        {
            Slot slot = gui.getScreenHandler().getSlot(i);

            if (slot.hasStack())
            {
                ItemStack stack = slot.getStack();

                if (stack.getCount() >= stackMaxSize(stack, true)) {
                    // ignore overstacking items.
                    continue;
                }

                ItemType key = new ItemType(stack);
                int slotNum = nonFullStacks.getOrDefault(key, -1);

                if (slotNum == -1)
                {
                    nonFullStacks.put(key, i);
                }
                else
                {
                    if (addStackTo(gui, slot, gui.getScreenHandler().getSlot(slotNum)))
                    {
                        nonFullStacks.put(key, i);
                    }
                }
            }
        }
    }

    /*
    private static class SlotVerticalSorterSlots implements Comparator<Slot>
    {
        private final boolean topToBottom;

        public SlotVerticalSorterSlots(boolean topToBottom)
        {
            this.topToBottom = topToBottom;
        }

        @Override
        public int compare(Slot slot1, Slot slot2)
        {
            if (slot1.yPos == slot2.yPos)
            {
                return (slot1.id < slot2.id) == this.topToBottom ? -1 : 1;
            }

            return (slot1.yPos < slot2.yPos) == this.topToBottom ? -1 : 1;
        }
    }
    */

    private static class SlotVerticalSorterSlotNumbers implements IntComparator
    {
        private final ScreenHandler container;
        private final boolean topToBottom;

        public SlotVerticalSorterSlotNumbers(ScreenHandler container, boolean topToBottom)
        {
            this.container = container;
            this.topToBottom = topToBottom;
        }

        @Override
        public int compare(int slotNum1, int slotNum2)
        {
            if (Objects.equals(slotNum1, slotNum2))
            {
                return 0;
            }

            Slot slot1 = this.container.getSlot(slotNum1);
            Slot slot2 = this.container.getSlot(slotNum2);

            if (slot1.y == slot2.y)
            {
                return (slot1.id < slot2.id) == this.topToBottom ? -1 : 1;
            }

            return (slot1.y < slot2.y) == this.topToBottom ? -1 : 1;
        }
    }

    public static void clickSlot(HandledScreen<? extends ScreenHandler> gui,
                                 int slotNum,
                                 int mouseButton,
                                 SlotActionType type)
    {
        if (slotNum >= 0 && slotNum < gui.getScreenHandler().slots.size())
        {
            Slot slot = gui.getScreenHandler().getSlot(slotNum);
            clickSlot(gui, slot, slotNum, mouseButton, type);
        }
        else
        {
            try
            {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.interactionManager.clickSlot(gui.getScreenHandler().syncId, slotNum, mouseButton, type, mc.player);
            }
            catch (Exception e)
            {
                ItemScroller.logger.warn("Exception while emulating a slot click: gui: '{}', slotNum: {}, mouseButton; {}, SlotActionType: {}",
                        gui.getClass().getName(), slotNum, mouseButton, type, e);
            }
        }
    }

    public static void clickSlot(HandledScreen<? extends ScreenHandler> gui,
                                 Slot slot,
                                 int slotNum,
                                 int mouseButton,
                                 SlotActionType type)
    {
        try
        {
            AccessorUtils.handleMouseClick(gui, slot, slotNum, mouseButton, type);
        }
        catch (Exception e)
        {
            ItemScroller.logger.warn("Exception while emulating a slot click: gui: '{}', slotNum: {}, mouseButton; {}, SlotActionType: {}",
                    gui.getClass().getName(), slotNum, mouseButton, type, e);
        }
    }

    public static void leftClickSlot(HandledScreen<? extends ScreenHandler> gui, Slot slot, int slotNumber)
    {
        clickSlot(gui, slot, slotNumber, 0, SlotActionType.PICKUP);
    }

    public static void rightClickSlot(HandledScreen<? extends ScreenHandler> gui, Slot slot, int slotNumber)
    {
        clickSlot(gui, slot, slotNumber, 1, SlotActionType.PICKUP);
    }

    public static void shiftClickSlot(HandledScreen<? extends ScreenHandler> gui, Slot slot, int slotNumber)
    {
        clickSlot(gui, slot, slotNumber, 0, SlotActionType.QUICK_MOVE);
    }

    public static void leftClickSlot(HandledScreen<? extends ScreenHandler> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.PICKUP);
    }

    public static void rightClickSlot(HandledScreen<? extends ScreenHandler> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 1, SlotActionType.PICKUP);
    }

    public static void shiftClickSlot(HandledScreen<? extends ScreenHandler> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.QUICK_MOVE);
    }

    public static void dropItemsFromCursor(HandledScreen<? extends ScreenHandler> gui)
    {
        clickSlot(gui, -999, 0, SlotActionType.PICKUP);
    }

    public static void dropItem(HandledScreen<? extends ScreenHandler> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.THROW);
    }

    public static void dropStack(HandledScreen<? extends ScreenHandler> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 1, SlotActionType.THROW);
    }

    public static void swapSlots(HandledScreen<? extends ScreenHandler> gui, int slotNum, int otherSlot)
    {
        //System.out.printf("swapSlots: [%d -> %d]\n", slotNum, otherSlot);

        clickSlot(gui, slotNum, 8, SlotActionType.SWAP);
        clickSlot(gui, otherSlot, 8, SlotActionType.SWAP);
        clickSlot(gui, slotNum, 8, SlotActionType.SWAP);
    }

    private static void dragSplitItemsIntoSlots(HandledScreen<? extends ScreenHandler> gui,
                                                IntArrayList targetSlots)
    {
        ItemStack stackInCursor = gui.getScreenHandler().getCursorStack();

        if (isStackEmpty(stackInCursor))
        {
            return;
        }

        if (targetSlots.size() == 1)
        {
            leftClickSlot(gui, targetSlots.getInt(0));
            return;
        }

        int numSlots = gui.getScreenHandler().slots.size();

        // Start the drag
        clickSlot(gui, -999, 0, SlotActionType.QUICK_CRAFT);

        for (int slotNum : targetSlots)
        {
            if (slotNum >= numSlots)
            {
                break;
            }

            clickSlot(gui, slotNum, 1, SlotActionType.QUICK_CRAFT);
        }

        // End the drag
        clickSlot(gui, -999, 2, SlotActionType.QUICK_CRAFT);
    }

    /**************************************************************
     * Compatibility code for pre-1.11 vs. 1.11+
     * Well kind of, as in make the differences minimal,
     * only requires changing these things for the ItemStack
     * related changes.
     *************************************************************/

    public static final ItemStack EMPTY_STACK = ItemStack.EMPTY;

    public static boolean isStackEmpty(ItemStack stack)
    {
        return stack.isEmpty();
    }

    public static int getStackSize(ItemStack stack)
    {
        return stack.getCount();
    }

    public static void setStackSize(ItemStack stack, int size)
    {
        stack.setCount(size);
    }

    public static ItemStack copyStack(ItemStack stack, boolean empty)
    {
        if (empty)
            return stack.copyAndEmpty();
        else
            return stack.copy();
    }
}
