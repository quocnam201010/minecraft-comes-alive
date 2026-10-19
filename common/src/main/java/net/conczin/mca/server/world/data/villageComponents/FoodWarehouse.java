package net.conczin.mca.server.world.data.villageComponents;

import net.conczin.mca.MCA;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.server.world.data.Building;
import net.conczin.mca.server.world.data.Village;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.level.block.Block;

import java.util.*;

public final class FoodWarehouse {
    public static final String TYPE = "food_warehouse";
    public static final int REQUIRED_CHESTS = 8;
    private static final TagKey<Block> CHESTS = TagKey.create(Registries.BLOCK, MCA.locate("chests"));
    private static final Comparator<BlockPos> CHEST_ORDER = Comparator.comparingInt((BlockPos pos) -> pos.getY())
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);

    private FoodWarehouse() { }

    public static int foodPoints(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (stack.isEmpty() || food == null || !Float.isFinite(food.saturation())) return 0;
        return Math.max(0, (int) Math.floor((double) food.nutrition() + food.saturation()));
    }

    public static int feed(VillagerEntityMCA villager, int capacity) {
        if (!(villager.level() instanceof ServerLevel level) || capacity <= 0) return 0;
        Optional<Village> home = villager.getResidency().getHomeVillage();
        if (home.isEmpty() || !home.get().getPolitics().canUseWarehouse(villager)) return 0;
        Village village = home.get();
        int budget = Math.min(capacity, village.getPolitics().getFoodBudget());
        int consumed = 0;
        Set<BlockPos> visited = new HashSet<>();
        List<Building> warehouses = village.getBuildings().values().stream()
                .filter(room -> TYPE.equals(room.getType()) && room.isComplete())
                .sorted(Comparator.comparingInt(Building::getId)).toList();
        for (Building warehouse : warehouses) {
            List<BlockPos> chests = warehouse.getBlockPosStream().distinct().sorted(CHEST_ORDER)
                    .filter(pos -> level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
                    .filter(pos -> level.getBlockState(pos).is(CHESTS))
                    .filter(pos -> level.getBlockEntity(pos) instanceof Container).toList();
            if (chests.size() < REQUIRED_CHESTS) continue;
            for (BlockPos pos : chests) {
                if (!visited.add(pos)) continue;
                Container chest = (Container) level.getBlockEntity(pos);
                if (chest == null) continue;
                consumed += consume(level, pos, chest, budget - consumed);
                if (consumed >= budget) return consumed;
            }
        }
        return consumed;
    }

    static int consume(ServerLevel level, BlockPos pos, Container chest, int budget) {
        int consumed = 0;
        for (int slot = 0; slot < chest.getContainerSize() && consumed < budget; slot++) {
            ItemStack stack = chest.getItem(slot);
            int points = foodPoints(stack);
            if (points <= 0 || points > budget - consumed || !VillagerEntityMCA.isSafeFood(stack)) continue;
            int amount = Math.min(stack.getCount(), (budget - consumed) / points);
            UseRemainder remainder = stack.get(DataComponents.USE_REMAINDER);
            chest.removeItem(slot, amount);
            consumed += amount * points;
            if (remainder != null) {
                for (int i = 0; i < amount; i++) insertRemainder(level, pos, chest, remainder.convertInto().create());
            }
            chest.setChanged();
        }
        return consumed;
    }

    private static void insertRemainder(ServerLevel level, BlockPos pos, Container chest, ItemStack remainder) {
        for (int slot = 0; slot < chest.getContainerSize() && !remainder.isEmpty(); slot++) {
            ItemStack existing = chest.getItem(slot);
            if (existing.isEmpty()) {
                int amount = Math.min(remainder.getCount(), chest.getMaxStackSize(remainder));
                chest.setItem(slot, remainder.split(amount));
            } else if (ItemStack.isSameItemSameComponents(existing, remainder)) {
                int amount = Math.min(remainder.getCount(), chest.getMaxStackSize(existing) - existing.getCount());
                if (amount > 0) { existing.grow(amount); remainder.shrink(amount); }
            }
        }
        if (!remainder.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, remainder);
    }
}
