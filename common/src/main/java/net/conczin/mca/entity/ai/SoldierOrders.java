package net.conczin.mca.entity.ai;

import net.conczin.mca.entity.VillagerEntityMCA;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Persistent orders; entity lookups only ever inspect already loaded entities. */
public final class SoldierOrders {
    public static final int DEFAULT_DITCH_DISTANCE = 24;
    private final VillagerEntityMCA soldier;
    private UUID commander;
    private UUID leader;
    private UUID target;
    private final List<UUID> queuedTargets = new ArrayList<>();
    private BlockPos destination;
    private BlockPos holdPosition;
    private int ditchDistance = DEFAULT_DITCH_DISTANCE;
    private int lastLeaderAttack = -1;
    private boolean returning;

    public SoldierOrders(VillagerEntityMCA soldier) { this.soldier = soldier; }
    public boolean hasDestination() { return destination != null || holdPosition != null; }
    public int getDitchDistance() { return ditchDistance; }
    public Optional<BlockPos> getDestination() { return Optional.ofNullable(destination); }

    public static boolean canCommand(ServerPlayer player, VillagerEntityMCA soldier) {
        return soldier.isAlive() && soldier.isGuard() && soldier.level() == player.level()
                && soldier.getResidency().getHomeVillage().filter(home -> home.getPolitics().canCommand(player)).isPresent();
    }

    public void clearCombat() {
        target = null;
        queuedTargets.clear();
        soldier.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.ARMY_ATTACK_TARGET);
        soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.NEAREST_GUARD_ENEMY);
        soldier.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        soldier.getBrain().eraseMemory(MemoryModuleType.PATH);
        soldier.getNavigation().stop();
        soldier.stopUsingItem();
    }

    public void movement(ServerPlayer issuer, MoveState state) {
        destination = null;
        holdPosition = state == MoveState.STAY ? soldier.blockPosition().immutable() : null;
        leader = state == MoveState.FOLLOW ? issuer.getUUID() : null;
        commander = issuer.getUUID();
        returning = false;
        lastLeaderAttack = issuer.getLastHurtMobTimestamp();
        clearCombat();
        soldier.getVillagerBrain().abandonJob();
        soldier.getVillagerBrain().setMoveState(state, state == MoveState.FOLLOW ? issuer : null);
    }

    public void goTo(ServerPlayer issuer, BlockPos position) {
        movement(issuer, MoveState.MOVE);
        destination = position.immutable();
        soldier.refreshBrain(issuer.level());
        tick();
    }

    public boolean validTarget(LivingEntity enemy) {
        if (enemy == soldier || !enemy.isAlive() || enemy.isRemoved() || enemy.level() != soldier.level()
                || enemy.getUUID().equals(commander) || enemy instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        if (enemy instanceof VillagerEntityMCA ally && ally.isGuard()
                && ally.getResidency().getVillageId() == soldier.getResidency().getVillageId()) return false;
        return soldier.canAttack(enemy);
    }

    public void attack(ServerPlayer issuer, LivingEntity enemy) {
        attack(issuer, List.of(enemy));
    }

    public void attack(ServerPlayer issuer, List<LivingEntity> enemies) {
        movement(issuer, MoveState.MOVE);
        if (enemies.isEmpty()) return;
        target = enemies.getFirst().getUUID();
        enemies.stream().skip(1).map(LivingEntity::getUUID).forEach(queuedTargets::add);
        soldier.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, enemies.getFirst());
    }

    public boolean isOrderedTarget(LivingEntity enemy) {
        return enemy.getUUID().equals(target) && validTarget(enemy) && canEngage(enemy);
    }

    public void setDitchDistance(int distance) {
        if (distance < 4 || distance > 128) throw new IllegalArgumentException("Ditch distance must be 4 to 128 blocks");
        ditchDistance = distance;
    }

    private Optional<Player> followedPlayer() {
        return soldier.getBrain().getMemoryInternal(MemoryModuleTypeMCA.PLAYER_FOLLOWING)
                .filter(player -> leader != null && player.getUUID().equals(leader) && player.isAlive()
                        && player.level() == soldier.level() && !player.isRemoved());
    }

    /** Also used by the sensor so a discarded enemy cannot be reacquired immediately. */
    public boolean canEngage(LivingEntity enemy) {
        if (leader == null) return true;
        return !returning && followedPlayer().filter(player -> soldier.distanceToSqr(player) < (double) ditchDistance * ditchDistance
                && enemy.distanceToSqr(player) <= (double) ditchDistance * ditchDistance).isPresent();
    }

    public void tick() {
        if (!(soldier.level() instanceof ServerLevel level) || !soldier.isAlive() || !soldier.isGuard()) return;
        if (commander != null && soldier.tickCount % 20 == 0) {
            ServerPlayer issuer = level.getServer().getPlayerList().getPlayer(commander);
            boolean appointed = soldier.getResidency().getHomeVillage()
                    .filter(home -> home.getPolitics().hasCommandAuthority(commander)).isPresent();
            if (!appointed || issuer != null && !canCommand(issuer, soldier)) {
                clearCombat();
                destination = null;
                holdPosition = null;
                leader = null;
                commander = null;
                soldier.getVillagerBrain().setMoveState(MoveState.STAY, null);
                return;
            }
        }
        if (leader != null) {
            Player player = level.getPlayerByUUID(leader);
            if (player instanceof ServerPlayer online && canCommand(online, soldier)
                    && followedPlayer().isEmpty()) soldier.getVillagerBrain().setMoveState(MoveState.FOLLOW, online);
            Optional<Player> followed = followedPlayer();
            if (followed.isEmpty()) {
                clearCombat();
                if (soldier.getVillagerBrain().getMoveState() != MoveState.STAY)
                    soldier.getVillagerBrain().setMoveState(MoveState.STAY, null);
                return;
            }
            player = followed.get();
            double distance = soldier.distanceToSqr(player);
            if (distance >= (double) ditchDistance * ditchDistance) {
                returning = true;
                clearCombat();
            } else if (returning && distance <= 16) returning = false;
            LivingEntity enemy = player.getLastHurtMob();
            if (!returning && player.getLastHurtMobTimestamp() != lastLeaderAttack) {
                lastLeaderAttack = player.getLastHurtMobTimestamp();
                if (enemy != null && player.tickCount - lastLeaderAttack <= 100 && validTarget(enemy) && canEngage(enemy)) {
                    target = enemy.getUUID();
                    soldier.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, enemy);
                }
            }
        }
        if (target != null) {
            if (level.getEntity(target) instanceof LivingEntity enemy && validTarget(enemy) && canEngage(enemy)) {
                soldier.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, enemy);
                soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.ARMY_ATTACK_TARGET);
            } else {
                List<UUID> remaining = List.copyOf(queuedTargets);
                clearCombat();
                if (!remaining.isEmpty()) {
                    target = remaining.getFirst();
                    queuedTargets.addAll(remaining.subList(1, remaining.size()));
                }
            }
        }
        BlockPos orderedPosition = destination != null ? destination : holdPosition;
        if (orderedPosition != null && !soldier.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            if (soldier.blockPosition().distSqr(orderedPosition) <= 2.25D) {
                if (destination == null) return;
                holdPosition = destination;
                destination = null;
                soldier.getVillagerBrain().setMoveState(MoveState.STAY, null);
            } else {
                // A destination outside loaded terrain remains pending without loading it.
                BlockPos step = orderedPosition;
                if (!level.getChunkSource().hasChunk(step.getX() >> 4, step.getZ() >> 4)) {
                    var direction = net.minecraft.world.phys.Vec3.atBottomCenterOf(step).subtract(soldier.position()).normalize();
                    step = BlockPos.containing(soldier.position().add(direction.scale(16)));
                }
                if (level.getChunkSource().hasChunk(step.getX() >> 4, step.getZ() >> 4))
                    soldier.moveTowardsPersistent(step, 0.75F, 1);
            }
        }
    }

    public void save(CompoundTag data) {
        CompoundTag tag = new CompoundTag();
        if (commander != null) tag.putString("commander", commander.toString());
        if (leader != null) tag.putString("leader", leader.toString());
        if (target != null) tag.putString("target", target.toString());
        tag.putString("queue", String.join(",", queuedTargets.stream().map(UUID::toString).toList()));
        if (destination != null) tag.putLong("destination", destination.asLong());
        if (holdPosition != null) tag.putLong("holdPosition", holdPosition.asLong());
        tag.putInt("ditchDistance", ditchDistance);
        data.put("SoldierOrders", tag);
    }

    public void load(CompoundTag data) {
        CompoundTag tag = data.getCompound("SoldierOrders").orElseGet(CompoundTag::new);
        commander = uuid(tag, "commander");
        leader = uuid(tag, "leader");
        target = uuid(tag, "target");
        queuedTargets.clear();
        for (String id : tag.getString("queue").orElse("").split(",")) {
            if (queuedTargets.size() >= 31) break;
            try { queuedTargets.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
        }
        destination = tag.getLong("destination").map(BlockPos::of).orElse(null);
        holdPosition = tag.getLong("holdPosition").map(BlockPos::of).orElse(null);
        ditchDistance = Math.clamp(tag.getInt("ditchDistance").orElse(DEFAULT_DITCH_DISTANCE), 4, 128);
        returning = false;
        lastLeaderAttack = -1;
    }

    private static UUID uuid(CompoundTag tag, String key) {
        try { return UUID.fromString(tag.getString(key).orElse("")); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
