package net.conczin.mca.network.c2s;

import net.conczin.mca.MCA;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.network.HandleablePayload;
import net.conczin.mca.network.Network;
import net.conczin.mca.network.s2c.ArmyOverviewResponse;
import net.conczin.mca.server.world.data.Village;
import net.conczin.mca.server.world.data.VillageManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.*;

public record ArmyOverviewRequest(int villageId) implements HandleablePayload {
    public static final Type<ArmyOverviewRequest> TYPE = new Type<>(MCA.locate("army_overview"));
    public static final StreamCodec<FriendlyByteBuf, ArmyOverviewRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ArmyOverviewRequest::villageId, ArmyOverviewRequest::new);

    public static CompoundTag snapshot(ServerPlayer player, Village village) {
        CompoundTag data = new CompoundTag();
        ListTag soldiers = new ListTag();
        ListTag targets = new ListTag();
        boolean command = village.getPolitics().canCommand(player);
        data.putBoolean("canCommand", command);
        var army = village.getResidents(player.level()).stream().filter(npc -> npc.isGuard() && npc.isAlive())
                .sorted(Comparator.comparing(VillagerEntityMCA::getUUID)).limit(1024).toList();
        for (VillagerEntityMCA soldier : army) {
            CompoundTag tag = entity(soldier);
            tag.putInt("ditchDistance", soldier.getSoldierOrders().getDitchDistance());
            tag.putString("profession", soldier.getProfessionText().getString());
            tag.putString("movement", soldier.getVillagerBrain().getMoveState().name());
            soldiers.add(tag);
        }
        if (command) {
            Set<UUID> included = new HashSet<>();
            for (VillagerEntityMCA soldier : army) {
                for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class,
                        soldier.getBoundingBox().inflate(128), enemy -> enemy.isAlive() && enemy != player
                                && (!(enemy instanceof Player p) || !p.isCreative() && !p.isSpectator()))) {
                    if (targets.size() >= 1024) break;
                    if (included.add(target.getUUID()) && soldier.getSoldierOrders().validTarget(target)) targets.add(entity(target));
                }
                if (targets.size() >= 1024) break;
            }
        }
        data.put("soldiers", soldiers);
        data.put("targets", targets);
        return data;
    }

    private static CompoundTag entity(LivingEntity entity) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entity.getUUID().toString());
        tag.putString("name", entity.getName().getString());
        tag.putDouble("x", entity.getX());
        tag.putDouble("y", entity.getY());
        tag.putDouble("z", entity.getZ());
        return tag;
    }

    @Override public void handleServer(ServerPlayer player) {
        Optional<Village> village = VillageManager.get(player.level()).getOrEmpty(villageId)
                .filter(candidate -> candidate.isWithinBorder(player) || GetVillageRequest.canViewRemotely(candidate, player));
        // Also clear stale client selections after authority is revoked or a village disappears.
        Network.sendToPlayer(new ArmyOverviewResponse(villageId,
                village.map(candidate -> snapshot(player, candidate)).orElseGet(CompoundTag::new)), player);
    }
    @Override public Type<ArmyOverviewRequest> type() { return TYPE; }
}
