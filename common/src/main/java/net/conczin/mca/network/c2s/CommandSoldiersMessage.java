package net.conczin.mca.network.c2s;

import net.conczin.mca.MCA;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.MoveState;
import net.conczin.mca.entity.ai.SoldierOrders;
import net.conczin.mca.network.HandleablePayload;
import net.conczin.mca.registry.ProfessionsMCA;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.border.WorldBorder;

import java.util.*;

public record CommandSoldiersMessage(int villageId, Action action, List<UUID> soldiers, BlockPos position,
                                     String target, int value) implements HandleablePayload {
    public enum Action { FOLLOW, STAY, FREE, MOVE_TO, ATTACK, GUARD, ARCHER, DITCH_DISTANCE, INVENTORY }
    public static final int MAX_SELECTION = 128;
    public static final Type<CommandSoldiersMessage> TYPE = new Type<>(MCA.locate("command_soldiers"));
    public static final StreamCodec<FriendlyByteBuf, CommandSoldiersMessage> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        buf.writeVarInt(message.villageId);
        buf.writeEnum(message.action);
        buf.writeVarInt(message.soldiers.size());
        message.soldiers.forEach(buf::writeUUID);
        buf.writeBlockPos(message.position);
        buf.writeUtf(message.target, 1183);
        buf.writeVarInt(message.value);
    }, buf -> {
        int village = buf.readVarInt();
        Action action = buf.readEnum(Action.class);
        int count = buf.readVarInt();
        if (count < 1 || count > MAX_SELECTION) throw new IllegalArgumentException("Invalid soldier selection");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        return new CommandSoldiersMessage(village, action, ids, buf.readBlockPos(), buf.readUtf(1183), buf.readVarInt());
    });
    public CommandSoldiersMessage { soldiers = List.copyOf(soldiers); }

    public boolean apply(ServerPlayer player) {
        if (soldiers.isEmpty() || soldiers.size() > MAX_SELECTION || new HashSet<>(soldiers).size() != soldiers.size()) return false;
        var home = net.conczin.mca.server.world.data.VillageManager.get(player.level()).getOrEmpty(villageId);
        if (home.isEmpty() || !home.get().getPolitics().canCommand(player)) return false;
        List<VillagerEntityMCA> selected = new ArrayList<>();
        for (UUID id : soldiers) {
            if (!(player.level().getEntity(id) instanceof VillagerEntityMCA soldier)
                    || soldier.getResidency().getVillageId() != villageId || !SoldierOrders.canCommand(player, soldier)) return false;
            selected.add(soldier);
        }
        List<LivingEntity> enemies = new ArrayList<>();
        if (action == Action.ATTACK) {
            String[] ids = target.split(",", -1);
            if (ids.length < 1 || ids.length > 32) return false;
            for (String rawId : ids) {
                UUID id;
                try { id = UUID.fromString(rawId); } catch (IllegalArgumentException ignored) { return false; }
                if (!(player.level().getEntity(id) instanceof LivingEntity enemy) || enemy == player) return false;
                for (VillagerEntityMCA soldier : selected)
                    if (!soldier.getSoldierOrders().validTarget(enemy) || soldier.distanceToSqr(enemy) > 256D * 256D) return false;
                if (!enemies.contains(enemy)) enemies.add(enemy);
            }
        }
        if (action == Action.MOVE_TO) {
            WorldBorder border = player.level().getWorldBorder();
            if (!border.isWithinBounds(position) || position.getY() < player.level().getMinY()
                    || position.getY() > player.level().getMaxY()) return false;
        }
        if (action == Action.DITCH_DISTANCE && (value < 4 || value > 128)) return false;
        if (action == Action.INVENTORY && (selected.size() != 1 || player.distanceToSqr(selected.getFirst()) > 64)) return false;
        for (VillagerEntityMCA soldier : selected) {
            switch (action) {
                case FOLLOW -> soldier.getSoldierOrders().movement(player, MoveState.FOLLOW);
                case STAY -> soldier.getSoldierOrders().movement(player, MoveState.STAY);
                case FREE -> soldier.getSoldierOrders().movement(player, MoveState.MOVE);
                case MOVE_TO -> soldier.getSoldierOrders().goTo(player, position);
                case ATTACK -> soldier.getSoldierOrders().attack(player, enemies);
                case GUARD, ARCHER -> {
                    soldier.setProfession(action == Action.GUARD ? ProfessionsMCA.GUARD : ProfessionsMCA.ARCHER);
                    soldier.refreshBrain(player.level());
                }
                case DITCH_DISTANCE -> soldier.getSoldierOrders().setDitchDistance(value);
                case INVENTORY -> player.openMenu(soldier);
            }
        }
        return true;
    }

    @Override public void handleServer(ServerPlayer player) {
        if (apply(player) && action != Action.INVENTORY) new ArmyOverviewRequest(villageId).handleServer(player);
    }
    @Override public Type<CommandSoldiersMessage> type() { return TYPE; }
}
