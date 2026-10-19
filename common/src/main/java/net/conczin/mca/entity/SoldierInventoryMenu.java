package net.conczin.mca.entity;

import net.conczin.mca.entity.ai.SoldierOrders;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Uses the vanilla four-row chest screen: cargo, then six player-owned equipment slots. */
public final class SoldierInventoryMenu extends AbstractContainerMenu {
    private final VillagerEntityMCA soldier;

    public SoldierInventoryMenu(int id, Inventory playerInventory, VillagerEntityMCA soldier) {
        super(MenuType.GENERIC_9x4, id);
        this.soldier = soldier;
        for (int i = 0; i < 27; i++) addSlot(new Slot(soldier.getInventory(), i, 8 + i % 9 * 18, 18 + i / 9 * 18));
        for (int i = 0; i < 6; i++) {
            final int equipment = i;
            addSlot(new Slot(soldier.getSoldierEquipment().getInventory(), i, 8 + i * 18, 72) {
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean mayPlace(ItemStack stack) {
                    return equipment < 2 || soldier.isEquippableInSlot(stack, SoldierEquipment.SLOTS[equipment]);
                }
            });
        }
        SimpleContainer padding = new SimpleContainer(3);
        for (int i = 0; i < 3; i++) addSlot(new Slot(padding, i, 116 + i * 18, 72) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int i = 0; i < 27; i++) addSlot(new Slot(playerInventory, 9 + i, 8 + i % 9 * 18, 103 + i / 9 * 18));
        for (int i = 0; i < 9; i++) addSlot(new Slot(playerInventory, i, 8 + i * 18, 161));
    }

    @Override public boolean stillValid(Player player) {
        return player instanceof ServerPlayer serverPlayer && player.distanceToSqr(soldier) <= 64
                && (player.isCreative() || SoldierOrders.canCommand(serverPlayer, soldier));
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 36 ? !moveItemStackTo(stack, 36, 72, true) : !moveItemStackTo(stack, 0, 27, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }
}
