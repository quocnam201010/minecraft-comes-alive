package net.conczin.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.MemoryModuleTypeMCA;
import net.conczin.mca.entity.ai.brain.WalkTargetFailureMemory;
import net.conczin.mca.entity.ai.navigation.MCAGroundPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;

public class FollowTask extends Behavior<VillagerEntityMCA> {
    private static final double TELEPORT_DISTANCE_SQUARED = 12.0D * 12.0D;
    private static final int TELEPORT_RETRY_TICKS = 10;

    private long nextTeleportAttempt;

    public FollowTask() {
        super(ImmutableMap.of(
                MemoryModuleTypeMCA.PLAYER_FOLLOWING, MemoryStatus.VALUE_PRESENT
        ));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel world, VillagerEntityMCA villager) {
        return villager.getBrain().getMemoryInternal(MemoryModuleTypeMCA.PLAYER_FOLLOWING).isPresent();
    }

    @Override
    protected boolean canStillUse(ServerLevel world, VillagerEntityMCA villager, long time) {
        return this.checkExtraStartConditions(world, villager);
    }

    @Override
    protected void tick(ServerLevel world, VillagerEntityMCA villager, long time) {
        villager.getBrain().getMemoryInternal(MemoryModuleTypeMCA.PLAYER_FOLLOWING).ifPresent(playerToFollow -> {
            if (!playerToFollow.isAlive() || playerToFollow.isSpectator() || playerToFollow.level() != world) {
                return;
            }

            if (villager.getVillagerBrain().isPanicking()
                    && villager.getBrain().getMemoryInternal(MemoryModuleType.HURT_BY_ENTITY)
                    .filter(livingEntity -> livingEntity == playerToFollow).isPresent()) {
                villager.getBrain().eraseMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING);
                return;
            }

            if (shouldYieldToGuardCombat(villager)) {
                return;
            }

            BlockPos followPosition = getFollowPosition(playerToFollow);

            // Like wolves, followers catch up even when no path to the player exists.
            // This is separate from optional teleporting for ordinary villager travel.
            if (villager.distanceToSqr(playerToFollow) >= TELEPORT_DISTANCE_SQUARED
                    && !villager.isPassenger() && !villager.isVehicle() && !villager.mayBeLeashed()
                    && time >= nextTeleportAttempt) {
                nextTeleportAttempt = time + TELEPORT_RETRY_TICKS;
                if (WanderOrTeleportToTargetTask.tryTeleport(world, villager, followPosition)) {
                    villager.getNavigation().stop();
                    villager.getBrain().eraseMemory(MemoryModuleType.PATH);
                    WalkTargetFailureMemory.clear(villager);
                }
            }

            float distance = villager.distanceTo(playerToFollow) - 2.0F;
            float speed = Math.min(1.0F, Math.max(0.6F, distance * 0.1F));
            float speedModifier = (villager.isPassenger() ? 1.7F : 0.8F) * speed;
            int verticalDistance = Math.abs(villager.getBlockY() - followPosition.getY());
            int closeEnoughDistance = verticalDistance > 1 ? 0 : 2;
            boolean climbing = villager.onClimbable()
                    || villager.getNavigation() instanceof MCAGroundPathNavigation navigation
                    && navigation.hasClimbablePathContext();
            if (climbing) {
                closeEnoughDistance = 0;
            }

            villager.getBrain().setMemory(
                    MemoryModuleType.WALK_TARGET,
                    new WalkTarget(
                            new BlockPosTracker(followPosition),
                            speedModifier,
                            closeEnoughDistance
                    )
            );
            villager.getBrain().setMemory(
                    MemoryModuleType.LOOK_TARGET,
                    new EntityTracker(playerToFollow, true)
            );
        });
    }

    private static BlockPos getFollowPosition(Entity target) {
        return target.onGround()
                ? target.getBlockPosBelowThatAffectsMyMovement().above()
                : target.blockPosition();
    }

    private boolean shouldYieldToGuardCombat(VillagerEntityMCA villager) {
        return villager.isGuard() && villager.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET);
    }
}
