package net.conczin.mca.network.s2c;

import net.conczin.mca.ClientProxy;
import net.conczin.mca.MCA;
import net.conczin.mca.network.HandleablePayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;

public record ArmyOverviewResponse(int villageId, CompoundTag data) implements HandleablePayload {
    public static final Type<ArmyOverviewResponse> TYPE = new Type<>(MCA.locate("army_overview_response"));
    public static final StreamCodec<FriendlyByteBuf, ArmyOverviewResponse> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ArmyOverviewResponse::villageId,
            ByteBufCodecs.COMPOUND_TAG, ArmyOverviewResponse::data, ArmyOverviewResponse::new);
    @Override public void handle(Player player) { ClientProxy.getNetworkHandler().handleArmyOverview(this); }
    @Override public Type<ArmyOverviewResponse> type() { return TYPE; }
}
