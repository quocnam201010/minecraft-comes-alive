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

    public void rememberPlayer(Player player) {
        if (!Objects.equals(knownPlayers.put(player.getUUID(), player.getName().getString()), player.getName().getString())) {
            village.markDirty();
        }
    }

    public boolean knowsPlayer(UUID player) {
        return knownPlayers.containsKey(player) || forcedRanks.containsKey(player) || village.hasReputation(player);
    }

    public void forceRank(Player player, Rank rank) {
        rememberPlayer(player);
        forcedRanks.put(player.getUUID(), rank);
        bannedPlayers.remove(player.getUUID());
        village.markDirty();
    }

    public void clearForcedRank(UUID player) {
        forcedRanks.remove(player);
        village.markDirty();
    }

    public void banPlayer(UUID player, String name) {
        bannedPlayers.put(player, name);
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
        CompoundTag ranks = tag.getCompound("forcedRanks").orElseGet(CompoundTag::new);
        for (String key : ranks.keySet()) {
            try { forcedRanks.put(UUID.fromString(key), Rank.valueOf(ranks.getString(key).orElse("PEASANT"))); }
            catch (IllegalArgumentException ignored) { }
        }
        loadNames(tag.getCompound("knownPlayers").orElseGet(CompoundTag::new), knownPlayers);
        loadNames(tag.getCompound("bannedPlayers").orElseGet(CompoundTag::new), bannedPlayers);
        hostilities.clear();
        ListTag rules = tag.getList("hostilities").orElseGet(ListTag::new);
        for (int i = 0; i < rules.size(); i++) {
            CompoundTag rule = rules.getCompound(i).orElseGet(CompoundTag::new);
            int id = rule.getInt("village").orElse(-1);
            if (id < 0 || id == village.getId()) continue;
            hostilities.put(id, new HostilityRule(id, Rank.fromName(rule.getString("minimumRank").orElse("peasant")),
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
        knownPlayers.putAll(other.knownPlayers);
        bannedPlayers.putAll(other.bannedPlayers);
        other.hostilities.forEach(hostilities::putIfAbsent);
        hostilities.remove(village.getId());
        hostilities.remove(other.village.getId());
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
