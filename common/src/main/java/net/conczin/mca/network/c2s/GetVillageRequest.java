package net.conczin.mca.network.c2s;

import net.conczin.mca.MCA;
import net.conczin.mca.network.HandleablePayload;
import net.conczin.mca.network.Network;
import net.conczin.mca.network.s2c.GetVillageFailedResponse;
import net.conczin.mca.network.s2c.GetVillageResponse;
import net.conczin.mca.resources.Rank;
import net.conczin.mca.resources.Tasks;
import net.conczin.mca.server.world.data.GraveyardManager;
import net.conczin.mca.server.world.data.Village;
import net.conczin.mca.server.world.data.VillageManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;

public record GetVillageRequest(int villageId, boolean commandView) implements HandleablePayload {
    public GetVillageRequest() { this(-1, false); }
    public GetVillageRequest(int villageId) { this(villageId, false); }
    public static final CustomPacketPayload.Type<GetVillageRequest> TYPE =
            new CustomPacketPayload.Type<>(MCA.locate("get_village_request"));
    public static final StreamCodec<FriendlyByteBuf, GetVillageRequest> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, GetVillageRequest::villageId,
                    ByteBufCodecs.BOOL, GetVillageRequest::commandView, GetVillageRequest::new);

    @Override
    public void handleServer(ServerPlayer player) {
        sendResponse(player, villageId, commandView);
    }

    static void sendResponse(ServerPlayer player) {
        sendResponse(player, -1);
    }

    public static boolean canViewRemotely(Village village, ServerPlayer player) {
        return village.getPolitics().knowsPlayer(player.getUUID())
                && (Tasks.getRank(village, player).isAtLeast(Rank.MAYOR) || village.getPolitics().canCommand(player));
    }

    static void sendResponse(ServerPlayer player, int villageId) {
        sendResponse(player, villageId, false);
    }

    public static Optional<Village> resolveVillage(ServerPlayer player, int villageId, boolean commandView) {
        Optional<Village> village = villageId < 0 ? Village.findNearest(player)
                : VillageManager.get(player.level()).getOrEmpty(villageId)
                    .filter(candidate -> canViewRemotely(candidate, player) || candidate.isWithinBorder(player));
        if (villageId < 0 && commandView && village.filter(candidate -> candidate.getPolitics().canCommand(player)).isEmpty()) {
            Optional<Village> commanded = java.util.stream.StreamSupport.stream(VillageManager.get(player.level()).spliterator(), false)
                    .filter(candidate -> candidate.getPolitics().knowsPlayer(player.getUUID()) && candidate.getPolitics().canCommand(player))
                    .min(java.util.Comparator.comparingInt(Village::getId));
            if (commanded.isPresent()) village = commanded;
        }
        if (village.isEmpty() && villageId < 0) {
            village = java.util.stream.StreamSupport.stream(VillageManager.get(player.level()).spliterator(), false)
                    .filter(candidate -> canViewRemotely(candidate, player)).findFirst();
        }
        return village;
    }

    private static void sendResponse(ServerPlayer player, int villageId, boolean commandView) {
        Optional<Village> village = resolveVillage(player, villageId, commandView);
        if (village.isPresent()) {
            if (village.get().isWithinBorder(player)) GraveyardManager.get(player.level()).reportToVillageManager(player);
            village.get().updateMaxPopulation();
            int reputation = village.get().getReputation(player);
            boolean isVillage = village.get().isVillage();
            Rank rank = Tasks.getRank(village.get(), player);
            Set<String> ids = Tasks.getCompletedIds(village.get(), player);
            village.get().getPolitics().rememberPlayer(player);
            Network.sendToPlayer(new GetVillageResponse(village.get(), rank, reputation, isVillage, ids, player), player);
        } else {
            Network.sendToPlayer(new GetVillageFailedResponse(), player);
        }
    }

    @Override
    public Type<GetVillageRequest> type() {
        return TYPE;
    }
}
