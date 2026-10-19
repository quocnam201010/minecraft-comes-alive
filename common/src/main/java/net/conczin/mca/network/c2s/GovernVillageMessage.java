package net.conczin.mca.network.c2s;

import net.conczin.mca.MCA;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.MemoryModuleTypeMCA;
import net.conczin.mca.network.HandleablePayload;
import net.conczin.mca.resources.Rank;
import net.conczin.mca.resources.Tasks;
import net.conczin.mca.server.world.data.Village;
import net.conczin.mca.server.world.data.VillageManager;
import net.conczin.mca.server.world.data.villageComponents.VillagePolitics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.*;

public record GovernVillageMessage(int villageId, Action action, String target, int value, int flags,
                                   List<String> professions) implements HandleablePayload {
    public enum Action { BAN_PLAYER, PARDON_PLAYER, SET_HOSTILITY, REMOVE_HOSTILITY, FOOD_POLICY }
    public static final Type<GovernVillageMessage> TYPE = new Type<>(MCA.locate("govern_village"));
    public static final StreamCodec<FriendlyByteBuf, GovernVillageMessage> STREAM_CODEC = StreamCodec.of((buf, message) -> {
        buf.writeVarInt(message.villageId());
        buf.writeEnum(message.action());
        buf.writeUtf(message.target(), 128);
        buf.writeVarInt(message.value());
        buf.writeVarInt(message.flags());
        buf.writeVarInt(message.professions().size());
        message.professions().forEach(id -> buf.writeUtf(id, 256));
    }, buf -> {
        int village = buf.readVarInt();
        Action action = buf.readEnum(Action.class);
        String target = buf.readUtf(128);
        int value = buf.readVarInt();
        int flags = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > 128) throw new IllegalArgumentException("Too many food policy professions");
        List<String> professions = new ArrayList<>();
        for (int i = 0; i < count; i++) professions.add(buf.readUtf(256));
        return new GovernVillageMessage(village, action, target, value, flags, professions);
    });

    public GovernVillageMessage { professions = List.copyOf(professions); }

    @Override
    public void handleServer(ServerPlayer player) {
        if (apply(player)) GetVillageRequest.sendResponse(player);
    }

    /** All permissions and references are rechecked on the authoritative server. */
    public boolean apply(ServerPlayer player) {
        Optional<Village> resolved = VillageManager.get(player.level()).getOrEmpty(villageId);
        if (resolved.isEmpty() || !resolved.get().isWithinBorder(player)
                || Tasks.getRank(resolved.get(), player) != Rank.MONARCH) {
            player.sendSystemMessage(Component.translatable("gui.blueprint.governance.denied"));
            return false;
        }
        Village village = resolved.get();
        VillagePolitics politics = village.getPolitics();
        Map<VillagerEntityMCA, net.minecraft.world.entity.LivingEntity> previousTargets = new HashMap<>();
        for (VillagerEntityMCA soldier : village.getResidents(player.level())) {
            soldier.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                    .filter(politics::isHostile).ifPresent(enemy -> previousTargets.put(soldier, enemy));
        }
        try {
            switch (action) {
                case BAN_PLAYER, PARDON_PLAYER -> {
                    UUID id = resolvePlayer(player, politics, target);
                    if (action == Action.BAN_PLAYER) {
                        if (id.equals(player.getUUID())) throw new IllegalArgumentException("Cannot ban yourself");
                        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(id);
                        String name = online != null ? online.getName().getString()
                                : politics.getKnownPlayers().getOrDefault(id, id.toString());
                        politics.banPlayer(id, name);
                    } else {
                        politics.pardonPlayer(id);
                    }
                }
                case SET_HOSTILITY, REMOVE_HOSTILITY -> {
                    int id = Integer.parseInt(target);
                    if (action == Action.REMOVE_HOSTILITY) politics.removeHostility(id);
                    else {
                        if (id == villageId || VillageManager.get(player.level()).getOrEmpty(id).isEmpty()
                                || value < 0 || value >= Rank.values().length || flags < 1 || flags > 3) {
                            throw new IllegalArgumentException("Invalid village targeting rule");
                        }
                        politics.setHostility(new VillagePolitics.HostilityRule(id, Rank.values()[value],
                                (flags & 1) != 0, (flags & 2) != 0));
                    }
                }
                case FOOD_POLICY -> {
                    if (professions.size() > 128) throw new IllegalArgumentException("Too many professions");
                    for (String id : professions) {
                        Identifier key = Identifier.tryParse(id);
                        if (key == null || !BuiltInRegistries.VILLAGER_PROFESSION.containsKey(key)) {
                            throw new IllegalArgumentException("Unknown profession");
                        }
                    }
                    politics.setFoodPolicy(VillagePolitics.FoodAccess.valueOf(target), value, professions);
                }
            }
        } catch (IllegalArgumentException error) {
            player.sendSystemMessage(Component.translatable("gui.blueprint.governance.invalid"));
            return false;
        }
        // Peace and pardons take effect immediately, including an attack already in progress.
        previousTargets.forEach((soldier, enemy) -> {
            if (!politics.isHostile(enemy)) {
                soldier.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.ARMY_ATTACK_TARGET);
                soldier.getBrain().eraseMemory(MemoryModuleTypeMCA.NEAREST_GUARD_ENEMY);
                soldier.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                soldier.getNavigation().stop();
                soldier.stopUsingItem();
            }
        });
        return true;
    }

    private static UUID resolvePlayer(ServerPlayer sender, VillagePolitics politics, String input) {
        try { return UUID.fromString(input); }
        catch (IllegalArgumentException ignored) { }
        ServerPlayer online = sender.level().getServer().getPlayerList().getPlayerByName(input);
        if (online != null) return online.getUUID();
        return java.util.stream.Stream.concat(politics.getKnownPlayers().entrySet().stream(),
                        politics.getBannedPlayers().entrySet().stream())
                .filter(entry -> entry.getValue().equalsIgnoreCase(input))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown player"));
    }

    @Override
    public CustomPacketPayload.Type<GovernVillageMessage> type() { return TYPE; }
}
