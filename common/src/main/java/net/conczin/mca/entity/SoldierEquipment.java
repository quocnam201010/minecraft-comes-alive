package net.conczin.mca.entity;

import net.conczin.mca.entity.ai.MemoryModuleTypeMCA;
import net.conczin.mca.entity.ai.ActivitiesMCA;
import net.conczin.mca.entity.ai.SoldierOrders;
import net.conczin.mca.registry.ProfessionsMCA;
import net.conczin.mca.util.InventoryUtils;
import net.conczin.mca.util.network.datasync.CDataParameter;
import net.conczin.mca.util.network.datasync.CParameter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;

/** Only gear deposited by players is withdrawable. Generated equipment never enters this container. */
public final class SoldierEquipment {
    public static final CDataParameter<Boolean> USE_DEFAULT_EQUIPMENT = CParameter.create("UseDefaultEquipment", true);
    public static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final VillagerEntityMCA soldier;
    private final UpdatableInventory inventory = new UpdatableInventory(SLOTS.length);

    public SoldierEquipment(VillagerEntityMCA soldier) {
        this.soldier = soldier;
        inventory.addListener(container -> {
            if (soldier.level().isClientSide()) return;
            if (!usesDefaultEquipment()) applySuppliedEquipment();
            else for (int i = 0; i < SLOTS.length; i++)
                if (!preserveHand(SLOTS[i])) soldier.setItemSlot(SLOTS[i], inventory.getItem(i));
            soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.WEARS_ARMOR);
        });
    }

    public UpdatableInventory getInventory() { return inventory; }
    public boolean usesDefaultEquipment() { return soldier.getTrackedValue(USE_DEFAULT_EQUIPMENT); }

    /** The interaction action is checked again on the server, including proximity. */
    public boolean toggleDefaultEquipment(ServerPlayer player) {
        if (soldier.level().isClientSide() || !SoldierOrders.canCommand(player, soldier)
                || soldier.distanceToSqr(player) > 64.0D) return false;
        soldier.setTrackedValue(USE_DEFAULT_EQUIPMENT, !usesDefaultEquipment());
        if (!usesDefaultEquipment()) applySuppliedEquipment();
        soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.WEARS_ARMOR);
        return true;
    }

    private boolean preserveHand(EquipmentSlot slot) {
        return (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND)
                && (soldier.isUsingRecoveryFood() || soldier.getBrain().isActive(ActivitiesMCA.GRIEVE)
                && soldier.getBrain().getMemoryInternal(MemoryModuleType.ATTACK_TARGET).isEmpty()
                && !soldier.getVillagerBrain().isPanicking());
    }

    private ItemStack suppliedItem(int index) {
        ItemStack explicit = inventory.getItem(index);
        if (!explicit.isEmpty()) return explicit;
        EquipmentSlot slot = SLOTS[index];
        if (slot == soldier.getDominantSlot()) {
            return (soldier.getProfession() == ProfessionsMCA.ARCHER
                    ? InventoryUtils.getBestRanged(soldier.getInventory())
                    : InventoryUtils.getBestSword(soldier.getInventory())).orElse(ItemStack.EMPTY);
        }
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) return ItemStack.EMPTY;
        return InventoryUtils.getBestArmor(soldier.getInventory(), slot).orElse(ItemStack.EMPTY);
    }

    public boolean needsSuppliedEquipmentRefresh() {
        for (int i = 0; i < SLOTS.length; i++) {
            if (preserveHand(SLOTS[i])) continue;
            ItemStack expected = suppliedItem(i);
            ItemStack equipped = soldier.getItemBySlot(SLOTS[i]);
            if (equipped != expected && !(equipped.isEmpty() && expected.isEmpty())) return true;
        }
        return false;
    }

    /** No generated fallback: empty supplied slots stay empty, including during combat. */
    public void applySuppliedEquipment() {
        for (int i = 0; i < SLOTS.length; i++)
            if (!preserveHand(SLOTS[i])) soldier.setItemSlot(SLOTS[i], suppliedItem(i));
    }
    public boolean has(EquipmentSlot slot) {
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) return !inventory.getItem(i).isEmpty();
        return false;
    }
    public void apply() {
        if (!usesDefaultEquipment()) applySuppliedEquipment();
        else for (int i = 0; i < SLOTS.length; i++)
            if (!inventory.getItem(i).isEmpty() && !preserveHand(SLOTS[i])) soldier.setItemSlot(SLOTS[i], inventory.getItem(i));
    }
    public void save(CompoundTag data) {
        data.put("SoldierEquipment", saveInventory(soldier.registryAccess(), inventory));
    }

    public static CompoundTag saveInventory(RegistryAccess registries, UpdatableInventory inventory) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        for (int i = 0; i < SLOTS.length; i++) output.store("slot" + i, ItemStack.OPTIONAL_CODEC, inventory.getItem(i));
        return output.buildResult();
    }
    public void load(CompoundTag data) {
        loadInventory(soldier.registryAccess(), inventory, data.getCompound("SoldierEquipment").orElseGet(CompoundTag::new));
        apply();
    }

    public static void loadInventory(RegistryAccess registries, UpdatableInventory inventory, CompoundTag data) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, registries, data);
        for (int i = 0; i < SLOTS.length; i++) inventory.setItem(i, input.read("slot" + i, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
