package net.conczin.mca.entity.ai;

import net.conczin.mca.MCA;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.server.world.data.villageComponents.FoodWarehouse;
import net.conczin.mca.util.network.datasync.CDataParameter;
import net.conczin.mca.util.network.datasync.CParameter;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** A saved counter of loaded entity ticks: unloading never accrues decay or meals. */
public final class PhysicalHealth {
    public static final int MAXIMUM = 1000;
    public static final int CYCLE_TICKS = 7 * 60 * 20;
    public static final int DECAY = 30;
    public static final String TIMER_KEY = "PhysicalHealthLoadedTicks";
    public static final CDataParameter<Integer> VALUE = CParameter.create("PhysicalHealth", 0);
    private static final Identifier MODIFIER = MCA.locate("physical_health");
    private final VillagerEntityMCA villager;
    private int loadedTicks;

    public PhysicalHealth(VillagerEntityMCA villager) { this.villager = villager; }

    public int get() { return Math.clamp(villager.getTrackedValue(VALUE), 0, MAXIMUM); }
    public int getLoadedTicks() { return loadedTicks; }

    public void set(int value) {
        villager.setTrackedValue(VALUE, Math.clamp(value, 0, MAXIMUM));
        updateAttributes();
    }

    public void tick() {
        if (!villager.isAlive() || villager.level().isClientSide()) return;
        if (++loadedTicks < CYCLE_TICKS) return;
        loadedTicks = 0;
        int decayed = Math.max(0, get() - DECAY);
        int food = FoodWarehouse.feed(villager, MAXIMUM - decayed);
        set(decayed + food);
    }

    private void updateAttributes() {
        if (villager.level().isClientSide()) return;
        double fraction = get() / (double) MAXIMUM;
        modifier(Attributes.MAX_HEALTH, 40.0D * fraction, AttributeModifier.Operation.ADD_VALUE);
        modifier(Attributes.ATTACK_DAMAGE, 2.0D * fraction, AttributeModifier.Operation.ADD_VALUE);
        modifier(Attributes.MOVEMENT_SPEED, 0.1D * fraction, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        if (villager.getHealth() > villager.getMaxHealth()) villager.setHealth(villager.getMaxHealth());
    }

    private void modifier(Holder<Attribute> attribute, double value, AttributeModifier.Operation operation) {
        AttributeInstance instance = villager.getAttribute(attribute);
        if (instance == null) return;
        instance.removeModifier(MODIFIER);
        if (value != 0.0D) instance.addTransientModifier(new AttributeModifier(MODIFIER, value, operation));
    }

    public void load(CompoundTag tag) {
        loadedTicks = Math.clamp(tag.getInt(TIMER_KEY).orElse(0), 0, CYCLE_TICKS - 1);
        set(get());
    }

    public void save(CompoundTag tag) { tag.putInt(TIMER_KEY, loadedTicks); }
}
