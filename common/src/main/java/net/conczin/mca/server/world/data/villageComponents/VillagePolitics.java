package net.conczin.mca.server.world.data.villageComponents;

import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.resources.Rank;
import net.conczin.mca.resources.Tasks;
import net.conczin.mca.server.world.data.Village;
import net.conczin.mca.server.world.data.VillageManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.*;

/** Village-local authority, diplomacy and warehouse access. */
public final class VillagePolitics {
    public enum FoodAccess { EVERYONE, MILITARY, SELECTED }

    public record HostilityRule(int villageId, Rank minimumRank, boolean targetPlayers, boolean targetVillagers) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("village", villageId);
            tag.putString("minimumRank", minimumRank.name());
            tag.putBoolean("players", targetPlayers);
            tag.putBoolean("villagers", targetVillagers);
            return tag;
        }
    }

    private final Village village;
    private final Map<UUID, Rank> forcedRanks = new HashMap<>();
    private UUID monarch;
    private final Set<UUID> commanders = new HashSet<>();
    private final Map<UUID, String> knownPlayers = new HashMap<>();
    private final Map<UUID, String> bannedPlayers = new HashMap<>();
    private final Map<Integer, HostilityRule> hostilities = new TreeMap<>();
    private FoodAccess foodAccess = FoodAccess.EVERYONE;
    private final Set<String> foodProfessions = new TreeSet<>();
    private int foodBudget = 200;

    public VillagePolitics(Village village) {
        this.village = village;
    }

    public Map<UUID, String> getBannedPlayers() { return Map.copyOf(bannedPlayers); }
    public Map<UUID, String> getKnownPlayers() { return Map.copyOf(knownPlayers); }
    public Collection<HostilityRule> getHostilities() { return List.copyOf(hostilities.values()); }
    public Optional<Rank> getForcedRank(UUID player) { return Optional.ofNullable(forcedRanks.get(player)); }
    public FoodAccess getFoodAccess() { return foodAccess; }
    public Set<String> getFoodProfessions() { return Set.copyOf(foodProfessions); }
    public int getFoodBudget() { return foodBudget; }
    public Optional<UUID> getMonarch() { return Optional.ofNullable(monarch); }
    public Set<UUID> getCommanders() { return Set.copyOf(commanders); }
    public boolean isCommander(UUID player) { return commanders.contains(player); }

    public void grantCommander(UUID player, String name, ServerPlayer online) {
        Rank rank = online != null ? Tasks.getRank(village, online) : forcedRanks.getOrDefault(player, Rank.OUTLAW);
        if (!rank.isAtLeast(Rank.PEASANT) || bannedPlayers.containsKey(player))
            throw new IllegalArgumentException("Commander appointment requires Peasant rank or higher");
        knownPlayers.put(player, name);
        commanders.add(player);
        village.markDirty();
    }

    public void revokeCommander(UUID player) {
        if (commanders.remove(player)) village.markDirty();
    }

    /** Saved authority for outstanding orders whose issuer may currently be offline. */
    public boolean hasCommandAuthority(UUID player) {
        return player.equals(monarch) || commanders.contains(player) && !bannedPlayers.containsKey(player)
                && forcedRanks.getOrDefault(player, Rank.PEASANT).isAtLeast(Rank.PEASANT);
    }

    /** The first eligible player claims the vacant throne; ownership is persistent. */
    public boolean claimMonarch(UUID player) {
        if (monarch != null) return monarch.equals(player);
        monarch = player;
        forcedRanks.put(player, Rank.MONARCH);
        village.markDirty();
        return true;
    }

    public boolean canCommand(ServerPlayer player) {
        Rank rank = Tasks.getRank(village, player);
        return rank == Rank.MONARCH || isCommander(player.getUUID()) && rank.isAtLeast(Rank.PEASANT);
    }

    public void rememberPlayer(Player player) {
        if (!Objects.equals(knownPlayers.put(player.getUUID(), player.getName().getString()), player.getName().getString())) {
            village.markDirty();
        }
    }

    public boolean knowsPlayer(UUID player) {
        return knownPlayers.containsKey(player) || forcedRanks.containsKey(player) || commanders.contains(player) || village.hasReputation(player);
    }

    public void forceRank(Player player, Rank rank) {
        rememberPlayer(player);
        appoint(player.getUUID(), player.getName().getString(), rank);
    }

    public void appoint(UUID player, String name, Rank rank) {
        if (rank == Rank.MONARCH) {
            if (monarch != null && !monarch.equals(player)) forcedRanks.put(monarch, Rank.MAYOR);
            monarch = player;
        } else if (player.equals(monarch)) monarch = null;
        knownPlayers.put(player, name);
        forcedRanks.put(player, rank);
        if (rank == Rank.OUTLAW) commanders.remove(player);
        bannedPlayers.remove(player);
        village.markDirty();
    }

    public void clearForcedRank(UUID player) {
        if (player.equals(monarch)) monarch = null;
        forcedRanks.remove(player);
        village.markDirty();
    }

    public void banPlayer(UUID player, String name) {
        if (player.equals(monarch)) throw new IllegalArgumentException("Cannot ban the reigning Monarch");
        bannedPlayers.put(player, name);
        commanders.remove(player);
        forcedRanks.remove(player);
        village.markDirty();
    }

    public void pardonPlayer(UUID player) {
        bannedPlayers.remove(player);
        village.markDirty();
    }

    public void setHostility(HostilityRule rule) {
        if (rule.villageId() == village.getId() || rule.villageId() < 0) {
            throw new IllegalArgumentException("Cannot target this village");
        }
        hostilities.put(rule.villageId(), rule);
        village.markDirty();
    }

    public void removeHostility(int targetVillage) {
        hostilities.remove(targetVillage);
        village.markDirty();
    }

    public boolean isPlayerOutlaw(Player player) {
        Rank forced = forcedRanks.get(player.getUUID());
        if (forced != null) return forced == Rank.OUTLAW;
        if (bannedPlayers.containsKey(player.getUUID())) return true;
        if (!(player instanceof ServerPlayer serverPlayer)) return false;
        VillageManager manager = VillageManager.get(serverPlayer.level());
        for (HostilityRule rule : hostilities.values()) {
            if (!rule.targetPlayers()) continue;
            Optional<Village> foreign = manager.getOrEmpty(rule.villageId());
            if (foreign.isPresent() && foreign.get().getPolitics().knowsPlayer(player.getUUID())
                    && Tasks.getRankIgnoringPolitics(foreign.get(), serverPlayer).isAtLeast(rule.minimumRank())) {
                return true;
            }
        }
        return false;
    }

    public boolean isHostile(LivingEntity target) {
        if (target instanceof Player player) return isPlayerOutlaw(player);
        if (target instanceof VillagerEntityMCA npc) {
            return npc.getResidency().getHomeVillage().map(home -> {
                HostilityRule rule = hostilities.get(home.getId());
                return home.getId() != village.getId() && rule != null && rule.targetVillagers();
            }).orElse(false);
        }
        return false;
    }

    public static boolean isEnemy(VillagerEntityMCA soldier, LivingEntity target) {
        return !soldier.level().isClientSide() && soldier.isGuard() && soldier.getResidency().getHomeVillage()
                .map(home -> home.getPolitics().isHostile(target)).orElse(false);
    }

    public boolean canUseWarehouse(VillagerEntityMCA npc) {
        return switch (foodAccess) {
            case EVERYONE -> true;
            case MILITARY -> npc.isGuard();
            case SELECTED -> foodProfessions.contains(BuiltInRegistries.VILLAGER_PROFESSION.getKey(npc.getProfession()).toString());
        };
    }

    public void setFoodPolicy(FoodAccess access, int budget, Collection<String> professions) {
        if (budget < 0 || budget > 200) throw new IllegalArgumentException("Food budget must be between 0 and 200");
        foodAccess = Objects.requireNonNull(access);
        foodBudget = budget;
        foodProfessions.clear();
        foodProfessions.addAll(professions);
        village.markDirty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag ranks = new CompoundTag();
        forcedRanks.forEach((id, rank) -> ranks.putString(id.toString(), rank.name()));
        tag.put("forcedRanks", ranks);
        CompoundTag roles = new CompoundTag();
        commanders.forEach(id -> roles.putBoolean(id.toString(), true));
        tag.put("commanders", roles);
        if (monarch != null) tag.putString("monarch", monarch.toString());
        tag.put("knownPlayers", namesToNbt(knownPlayers));
        tag.put("bannedPlayers", namesToNbt(bannedPlayers));
        ListTag rules = new ListTag();
        hostilities.values().forEach(rule -> rules.add(rule.save()));
        tag.put("hostilities", rules);
        tag.putString("foodAccess", foodAccess.name());
        tag.putInt("foodBudget", foodBudget);
        tag.putString("foodProfessions", String.join(",", foodProfessions));
        return tag;
    }

    public void load(CompoundTag tag) {
        forcedRanks.clear();
        commanders.clear();
        CompoundTag roles = tag.getCompound("commanders").orElseGet(CompoundTag::new);
        for (String key : roles.keySet()) {
            try { if (roles.getBoolean(key).orElse(false)) commanders.add(UUID.fromString(key)); }
            catch (IllegalArgumentException ignored) { }
        }
        CompoundTag ranks = tag.getCompound("forcedRanks").orElseGet(CompoundTag::new);
        for (String key : ranks.keySet()) {
            try {
                UUID id = UUID.fromString(key);
                String name = ranks.getString(key).orElse("PEASANT");
                // The old Commander rank overwrote the player's civil rank. Restore
                // ordinary civil progression and keep the separate military role.
                if ("COMMANDER".equals(name)) commanders.add(id);
                else forcedRanks.put(id, Rank.valueOf(name));
            }
            catch (IllegalArgumentException ignored) { }
        }
        monarch = null;
        try { monarch = UUID.fromString(tag.getString("monarch").orElse("")); }
        catch (IllegalArgumentException ignored) { }
        // Older saves allowed several forced Monarchs. Choose one deterministically
        // and retain the others as appointed Mayors.
        if (monarch == null) monarch = forcedRanks.entrySet().stream()
                .filter(entry -> entry.getValue() == Rank.MONARCH).map(Map.Entry::getKey).sorted().findFirst().orElse(null);
        normalizeMonarch();
        loadNames(tag.getCompound("knownPlayers").orElseGet(CompoundTag::new), knownPlayers);
        loadNames(tag.getCompound("bannedPlayers").orElseGet(CompoundTag::new), bannedPlayers);
        hostilities.clear();
        ListTag rules = tag.getList("hostilities").orElseGet(ListTag::new);
        for (int i = 0; i < rules.size(); i++) {
            CompoundTag rule = rules.getCompound(i).orElseGet(CompoundTag::new);
            int id = rule.getInt("village").orElse(-1);
            if (id < 0 || id == village.getId()) continue;
            String minimum = rule.getString("minimumRank").orElse("peasant");
            hostilities.put(id, new HostilityRule(id, "COMMANDER".equalsIgnoreCase(minimum) ? Rank.MAYOR : Rank.fromName(minimum),
                    rule.getBoolean("players").orElse(true), rule.getBoolean("villagers").orElse(true)));
        }
        try { foodAccess = FoodAccess.valueOf(tag.getString("foodAccess").orElse("EVERYONE")); }
        catch (IllegalArgumentException ignored) { foodAccess = FoodAccess.EVERYONE; }
        foodBudget = Math.clamp(tag.getInt("foodBudget").orElse(200), 0, 200);
        foodProfessions.clear();
        for (String id : tag.getString("foodProfessions").orElse("").split(",")) {
            if (!id.isBlank()) foodProfessions.add(id);
        }
    }

    public void merge(VillagePolitics other) {
        other.forcedRanks.forEach(forcedRanks::putIfAbsent);
        commanders.addAll(other.commanders);
        if (monarch == null) monarch = other.monarch;
        normalizeMonarch();
        knownPlayers.putAll(other.knownPlayers);
        bannedPlayers.putAll(other.bannedPlayers);
        other.hostilities.forEach(hostilities::putIfAbsent);
        hostilities.remove(village.getId());
        hostilities.remove(other.village.getId());
    }

    private void normalizeMonarch() {
        forcedRanks.replaceAll((id, rank) -> rank == Rank.MONARCH && !id.equals(monarch) ? Rank.MAYOR : rank);
        if (monarch != null) forcedRanks.put(monarch, Rank.MONARCH);
    }

    private static CompoundTag namesToNbt(Map<UUID, String> names) {
        CompoundTag tag = new CompoundTag();
        names.forEach((id, name) -> tag.putString(id.toString(), name));
        return tag;
    }

    private static void loadNames(CompoundTag tag, Map<UUID, String> names) {
        names.clear();
        for (String key : tag.keySet()) {
            try { names.put(UUID.fromString(key), tag.getString(key).orElse(key)); }
            catch (IllegalArgumentException ignored) { }
        }
    }
}
