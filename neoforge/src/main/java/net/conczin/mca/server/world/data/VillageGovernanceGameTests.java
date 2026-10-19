package net.conczin.mca.server.world.data;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.authlib.GameProfile;
import net.conczin.mca.MCA;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.VillagerFactory;
import net.conczin.mca.entity.ZombieVillagerEntityMCA;
import net.conczin.mca.registry.EntitiesMCA;
import net.conczin.mca.entity.ai.PhysicalHealth;
import net.conczin.mca.entity.ai.Traits;
import net.conczin.mca.entity.ai.brain.sensor.GuardEnemiesSensor;
import net.conczin.mca.neoforge.gametest.GameTest;
import net.conczin.mca.neoforge.gametest.PrefixGameTestTemplate;
import net.conczin.mca.network.c2s.GovernVillageMessage;
import net.conczin.mca.network.c2s.CommandSoldiersMessage;
import net.conczin.mca.network.c2s.ArmyOverviewRequest;
import net.conczin.mca.network.c2s.GetVillageRequest;
import net.conczin.mca.entity.ai.MoveState;
import net.conczin.mca.entity.ai.MemoryModuleTypeMCA;
import net.conczin.mca.entity.SoldierInventoryMenu;
import net.conczin.mca.entity.SoldierEquipment;
import net.conczin.mca.entity.EquipmentSet;
import net.conczin.mca.entity.ai.brain.tasks.EquipmentTask;
import net.conczin.mca.entity.interaction.Constraint;
import net.conczin.mca.registry.ProfessionsMCA;
import net.conczin.mca.resources.BuildingTypes;
import net.conczin.mca.resources.Rank;
import net.conczin.mca.resources.Tasks;
import net.conczin.mca.server.command.AdminCommand;
import net.conczin.mca.server.world.data.villageComponents.FoodWarehouse;
import net.conczin.mca.server.world.data.villageComponents.VillagePolitics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.conczin.mca.gametest.GameTestTerrain.prepareFlatArea;

@PrefixGameTestTemplate(false)
public final class VillageGovernanceGameTests {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_000_000);

    private VillageGovernanceGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void administratorCanForceAndClearRankForOneVillage(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            ServerPlayer player = fixture.player();
            Village village = fixture.village();
            Village other = new Village(NEXT_ID.incrementAndGet(), helper.getLevel());
            helper.assertTrue(Tasks.getRank(village, player) != Rank.MONARCH, "fixture already met Monarch requirements");
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            AdminCommand.register(dispatcher);
            CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
                    .withLevel(helper.getLevel()).withEntity(player);
            int result = dispatcher.execute("mca-admin forceRank @s monarch " + village.getId(), source);
            helper.assertTrue(result == 1 && Tasks.getRank(village, player) == Rank.MONARCH,
                    "forceRank did not bypass reputation, buildings and advancement requirements");
            helper.assertTrue(Tasks.getRank(other, player) != Rank.MONARCH, "forced rank leaked into another village");
            Village loaded = new Village(village.save(), helper.getLevel());
            helper.assertTrue(Tasks.getRank(loaded, player) == Rank.MONARCH, "forced rank did not survive saving");
            dispatcher.execute("mca-admin clearForcedRank @s " + village.getId(), source);
            helper.assertTrue(Tasks.getRank(village, player) != Rank.MONARCH, "clearing a forced rank did not restore requirements");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void bansChangeRankAndMilitaryTargetingAndPardonsStopCombat(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            ServerPlayer monarch = fixture.player();
            ServerPlayer target = mockPlayer(helper, fixture.base().east(3));
            try {
                fixture.village().getPolitics().forceRank(monarch, Rank.MONARCH);
                VillagerEntityMCA guard = fixture.soldier();
                helper.assertTrue(message(fixture, GovernVillageMessage.Action.BAN_PLAYER, target.getUUID().toString(), 0, 0)
                        .apply(monarch), "Monarch could not ban another player");
                helper.assertTrue(Tasks.getRank(fixture.village(), target) == Rank.OUTLAW,
                        "the banned player was not an outlaw to this village");
                helper.assertTrue(GuardEnemiesSensor.isGuardEnemy(target, guard), "guard did not recognize the banned player");
                ((TestPlayer) target).mode = GameType.CREATIVE;
                helper.assertTrue(!GuardEnemiesSensor.isGuardEnemy(target, guard), "ban bypassed Creative immunity");
                ((TestPlayer) target).mode = GameType.SURVIVAL;
                guard.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
                helper.assertTrue(message(fixture, GovernVillageMessage.Action.PARDON_PLAYER, target.getUUID().toString(), 0, 0)
                        .apply(monarch), "Monarch could not pardon the player");
                helper.assertTrue(!guard.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                                && !GuardEnemiesSensor.isGuardEnemy(target, guard), "pardon left the guard attacking");
                helper.succeed();
            } finally { target.discard(); }
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void foreignRankThresholdAppliesWorldwideAndIncludesHigherRanks(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            ServerPlayer target = foreign.player();
            foreign.village().getPolitics().forceRank(target, Rank.MAYOR);
            target.setPos(Vec3.atBottomCenterOf(fixture.base().east(3)));
            helper.assertTrue(!foreign.village().isWithinBorder(target), "target did not leave its home village");
            fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(
                    foreign.village().getId(), Rank.NOBLE, true, false));
            helper.assertTrue(GuardEnemiesSensor.isGuardEnemy(target, fixture.soldier()),
                    "a higher-ranked foreign player stopped being a target after travelling elsewhere");
            foreign.village().getPolitics().forceRank(target, Rank.MERCHANT);
            helper.assertTrue(!GuardEnemiesSensor.isGuardEnemy(target, fixture.soldier()),
                    "rule targeted a player below its minimum rank");
            helper.assertTrue(!GuardEnemiesSensor.isGuardEnemy(foreign.soldier(), fixture.soldier()),
                    "a players-only rule targeted NPC residents");
            fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(
                    foreign.village().getId(), Rank.MONARCH, false, true));
            helper.assertTrue(GuardEnemiesSensor.isGuardEnemy(foreign.soldier(), fixture.soldier()),
                    "NPC targeting incorrectly depended on the player rank filter");
            helper.assertTrue(!GuardEnemiesSensor.isGuardEnemy(fixture.civilian(), fixture.soldier()),
                    "army started targeting its own citizens");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void mutualHostilitiesDoNotRecurseThroughPoliticalRanks(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture other = fixture(helper, fixtureOrigin(helper).east(200))) {
            fixture.village().getPolitics().rememberPlayer(other.player());
            other.village().getPolitics().rememberPlayer(fixture.player());
            fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(other.village().getId(), Rank.PEASANT, true, true));
            other.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(fixture.village().getId(), Rank.PEASANT, true, true));
            helper.assertTrue(Tasks.getRank(fixture.village(), fixture.player()) == Rank.OUTLAW,
                    "mutual hostility lost the player's earned foreign rank");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void militaryBrainAcquiresForeignEnemyAndPeaceReleasesIt(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            VillagerEntityMCA soldier = fixture.soldier();
            VillagerEntityMCA enemy = foreign.soldier();
            enemy.setPos(soldier.position().add(5, 0, 0));
            fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(
                    foreign.village().getId(), Rank.PEASANT, false, true));
            soldier.refreshBrain(helper.getLevel());
            new TestSensor().scan(helper.getLevel(), soldier);
            soldier.getBrain().tick(helper.getLevel(), soldier);
            helper.assertTrue(soldier.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == enemy,
                    "the actual military brain did not acquire its declared foreign enemy");
            helper.assertTrue(message(fixture, GovernVillageMessage.Action.REMOVE_HOSTILITY,
                            Integer.toString(foreign.village().getId()), 0, 0).apply(fixture.player()),
                    "Monarch could not remove the targeting rule");
            helper.assertTrue(!soldier.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET),
                    "removing the rule left the military brain attacking");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void defendingArmyRetaliatesAgainstInvadersWithoutAttackingOwnSoldiers(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            VillagerEntityMCA attacker = fixture.soldier();
            VillagerEntityMCA defender = foreign.soldier();
            defender.setPos(attacker.position().add(2, 0, 0));
            fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(
                    foreign.village().getId(), Rank.PEASANT, false, true));
            defender.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker), 2.0F);
            helper.assertTrue(defender.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == attacker,
                    "friend filtering prevented a guard from defending against an invading villager");
            helper.assertTrue(attacker.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) != attacker,
                    "the invading army joined the defending village against its own soldier");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void governanceAllowsRemoteMonarchButRejectsForgedAndInvalidChanges(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            GovernVillageMessage ban = message(fixture, GovernVillageMessage.Action.BAN_PLAYER, UUID.randomUUID().toString(), 0, 0);
            helper.assertTrue(!ban.apply(fixture.player()), "non-Monarch forged a village ban");
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            fixture.player().setPos(Vec3.atBottomCenterOf(fixture.base().east(200)));
            helper.assertTrue(ban.apply(fixture.player()), "Monarch could not change a village remotely");
            helper.assertTrue(GetVillageRequest.canViewRemotely(fixture.village(), fixture.player()), "remote village selection rejected the Monarch");
            UUID banned = fixture.village().getPolitics().getBannedPlayers().keySet().iterator().next();
            fixture.village().getPolitics().pardonPlayer(banned);
            fixture.player().setPos(Vec3.atBottomCenterOf(fixture.base()));
            helper.assertTrue(!message(fixture, GovernVillageMessage.Action.BAN_PLAYER, fixture.player().getUUID().toString(), 0, 0)
                    .apply(fixture.player()), "Monarch could ban themselves");
            helper.assertTrue(!message(fixture, GovernVillageMessage.Action.SET_HOSTILITY, Integer.toString(fixture.village().getId()), 0, 3)
                    .apply(fixture.player()), "village could declare hostility against itself");
            for (int budget : new int[]{-1, 201}) {
                helper.assertTrue(!message(fixture, GovernVillageMessage.Action.FOOD_POLICY, "EVERYONE", budget, 0)
                        .apply(fixture.player()), "invalid food budget was accepted");
            }
            helper.assertTrue(fixture.village().getPolitics().getBannedPlayers().isEmpty()
                            && fixture.village().getPolitics().getFoodBudget() == 200,
                    "rejected requests still mutated the village");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void villagePoliciesSurviveSaveAndLoad(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            UUID banned = UUID.randomUUID();
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.NOBLE);
            fixture.village().getPolitics().banPlayer(banned, "Banned Citizen");
            fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(100, Rank.MAYOR, true, false));
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.SELECTED, 70, List.of("mca:archer"));
            Village loaded = new Village(fixture.village().save(), helper.getLevel());
            helper.assertTrue(Tasks.getRank(loaded, fixture.player()) == Rank.NOBLE
                            && loaded.getPolitics().getBannedPlayers().containsKey(banned)
                            && loaded.getPolitics().getHostilities().iterator().next().minimumRank() == Rank.MAYOR
                            && loaded.getPolitics().getFoodAccess() == VillagePolitics.FoodAccess.SELECTED
                            && loaded.getPolitics().getFoodBudget() == 70
                            && loaded.getPolitics().getFoodProfessions().equals(Set.of("mca:archer")),
                    "village rank, diplomacy or food policy did not survive saving");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void physicalHealthScalesAttributesAndNeverStacks(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            VillagerEntityMCA npc = fixture.civilian();
            npc.getTraits().removeTrait(Traits.TOUGH);
            npc.getTraits().removeTrait(Traits.WEAK);
            npc.updateAttributes();
            double health = npc.getMaxHealth();
            double damage = npc.getAttributeValue(Attributes.ATTACK_DAMAGE);
            double speed = npc.getAttributeValue(Attributes.MOVEMENT_SPEED);
            npc.getPhysicalHealth().set(500);
            close(helper, npc.getMaxHealth(), health + 20, "half-strength maximum HP");
            close(helper, npc.getAttributeValue(Attributes.ATTACK_DAMAGE), damage + 1, "half-strength attack damage");
            close(helper, npc.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * 1.05, "half-strength movement speed");
            for (int i = 0; i < 4; i++) npc.getPhysicalHealth().set(5000);
            helper.assertTrue(npc.getPhysicalHealth().get() == 1000, "physical health exceeded its cap");
            close(helper, npc.getMaxHealth(), health + 40, "maximum HP or modifier stacking");
            close(helper, npc.getAttributeValue(Attributes.ATTACK_DAMAGE), damage + 2, "maximum attack damage");
            close(helper, npc.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * 1.1, "compound movement multiplier");
            npc.setHealth(npc.getMaxHealth());
            npc.getPhysicalHealth().set(-100);
            close(helper, npc.getMaxHealth(), health, "zero physical health retained a buff");
            helper.assertTrue(npc.getHealth() <= npc.getMaxHealth(), "decay left health above the new maximum");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void physicalHealthTimerAndStatPersistWithoutOfflineCatchup(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            VillagerEntityMCA original = fixture.civilian();
            original.getPhysicalHealth().set(500);
            original.setHealth(original.getMaxHealth());
            for (int i = 0; i < 123; i++) original.getPhysicalHealth().tick();
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, original.registryAccess());
            original.addAdditionalSaveData(output);
            VillagerEntityMCA loaded = VillagerFactory.newVillager(helper.getLevel()).withAge(0).withPosition(original.position()).build();
            loaded.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, loaded.registryAccess(), output.buildResult()));
            helper.assertTrue(loaded.getPhysicalHealth().get() == 500 && loaded.getPhysicalHealth().getLoadedTicks() == 123,
                    "partial loaded-time cycle was lost on saving");
            close(helper, loaded.getHealth(), original.getHealth(), "loading discarded HP above the unbuffed maximum");
            // No entity ticks occur while unloaded. Restoring the entity must not advance the counter.
            for (int i = 123; i < PhysicalHealth.CYCLE_TICKS - 1; i++) loaded.getPhysicalHealth().tick();
            helper.assertTrue(loaded.getPhysicalHealth().get() == 500, "physical health decayed before seven loaded minutes");
            loaded.getPhysicalHealth().tick();
            helper.assertTrue(loaded.getPhysicalHealth().get() == 470 && loaded.getPhysicalHealth().getLoadedTicks() == 0,
                    "loaded-time decay did not occur exactly at the end of the saved cycle");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void physicalHealthAndPartialTimerSurviveZombificationAndCuring(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            VillagerEntityMCA original = fixture.civilian();
            original.getPhysicalHealth().set(500);
            for (int i = 0; i < 123; i++) original.getPhysicalHealth().tick();
            ZombieVillagerEntityMCA zombie = Objects.requireNonNull(
                    EntitiesMCA.MALE_ZOMBIE_VILLAGER.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
            zombie.copyVillagerAttributesFrom(original);
            zombie.setAgeState(original.getAgeState());
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, zombie.registryAccess());
            zombie.addAdditionalSaveData(output);
            ZombieVillagerEntityMCA reloadedZombie = Objects.requireNonNull(
                    EntitiesMCA.MALE_ZOMBIE_VILLAGER.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
            reloadedZombie.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING,
                    reloadedZombie.registryAccess(), output.buildResult()));
            VillagerEntityMCA cured = VillagerFactory.newVillager(helper.getLevel()).withAge(0).withPosition(original.position()).build();
            cured.copyVillagerAttributesFrom(reloadedZombie);
            helper.assertTrue(cured.getPhysicalHealth().get() == 500 && cured.getPhysicalHealth().getLoadedTicks() == 123,
                    "zombification, a restart or curing lost physical health or its partial timer");
            helper.assertTrue(cured.getAttribute(Attributes.MAX_HEALTH).getModifier(MCA.locate("physical_health")) != null,
                    "cured villager retained the stat but lost its attribute bonuses");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void warehouseRecognizesEightChestsAndConsumesInChestAndSlotOrder(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            helper.assertTrue(BuildingTypes.getInstance().getServerBuildingTypes().containsKey(FoodWarehouse.TYPE),
                    "food warehouse building definition was not loaded");
            Building seven = new Building(fixture.base());
            fixture.chests().stream().limit(7).forEach(pos -> seven.addBlock(Blocks.CHEST, pos));
            helper.assertTrue(!seven.matchesType(BuildingTypes.getInstance().getServerBuildingTypes().get(FoodWarehouse.TYPE)),
                    "seven chests qualified as a food warehouse");
            seven.addBlock(Blocks.CHEST, fixture.chests().getLast());
            helper.assertTrue(seven.matchesType(BuildingTypes.getInstance().getServerBuildingTypes().get(FoodWarehouse.TYPE)),
                    "eight chests did not qualify as a food warehouse");
            Container first = fixture.chest(0);
            first.setItem(0, new ItemStack(Items.COOKED_BEEF, 3));
            ItemStack moddedFood = new ItemStack(Items.PAPER, 3);
            moddedFood.set(DataComponents.FOOD, new FoodProperties(5, 12.0F, false));
            first.setItem(1, moddedFood);
            fixture.chest(1).setItem(0, new ItemStack(Items.BREAD, 10));
            helper.assertTrue(FoodWarehouse.foodPoints(first.getItem(0)) == 20, "cooked beef did not provide 20 points");
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.EVERYONE, 37, List.of());
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), 1000) == 37,
                    "warehouse did not fill the remaining budget using component-defined food");
            helper.assertTrue(first.getItem(0).getCount() == 2 && first.getItem(1).getCount() == 2
                            && fixture.chest(1).getItem(0).getCount() == 10,
                    "warehouse ignored deterministic chest/slot order or consumed too much");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void decayAndWarehouseMealShareOneIndependentVillagerCycle(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.chest(0).setItem(0, new ItemStack(Items.COOKED_BEEF, 64));
            VillagerEntityMCA npc = fixture.civilian();
            npc.getPhysicalHealth().set(500);
            for (int i = 0; i < PhysicalHealth.CYCLE_TICKS; i++) npc.getPhysicalHealth().tick();
            helper.assertTrue(npc.getPhysicalHealth().get() == 670 && fixture.chest(0).getItem(0).getCount() == 54,
                    "meal did not decay 30 first and then consume at most 200 food points");
            helper.assertTrue(fixture.soldier().getPhysicalHealth().getLoadedTicks() == 0,
                    "one villager's meal advanced another villager's independent timer");
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.EVERYONE, 0, List.of());
            for (int i = 0; i < PhysicalHealth.CYCLE_TICKS; i++) npc.getPhysicalHealth().tick();
            helper.assertTrue(npc.getPhysicalHealth().get() == 640 && fixture.chest(0).getItem(0).getCount() == 54,
                    "zero food budget stopped decay or still consumed food");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void foodAccessSupportsMilitaryAndSelectedProfessions(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.chest(0).setItem(0, new ItemStack(Items.COOKED_BEEF, 64));
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.MILITARY, 40, List.of());
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), 200) == 0
                            && FoodWarehouse.feed(fixture.soldier(), 200) == 40,
                    "military-only food access was not enforced");
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.SELECTED, 20, List.of("minecraft:none"));
            helper.assertTrue(FoodWarehouse.feed(fixture.soldier(), 200) == 0
                            && FoodWarehouse.feed(fixture.civilian(), 200) == 20,
                    "selected-profession access was not enforced");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void warehouseRequiresRealChestsPreservesBowlsAndDoesNotLoadChunks(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.chest(0).setItem(0, Items.MUSHROOM_STEW.getDefaultInstance());
            int points = FoodWarehouse.foodPoints(fixture.chest(0).getItem(0));
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), points) == points
                            && fixture.chest(0).getItem(0).is(Items.BOWL), "consumed stew did not return its bowl");
            fixture.chest(0).setItem(0, new ItemStack(Items.COOKED_BEEF, 64));
            helper.getLevel().setBlock(fixture.chests().getLast(), Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), 200) == 0,
                    "stale building metadata allowed feeding with only seven actual chests");
            BlockPos distant = fixture.base().offset(4096, 0, 4096);
            fixture.warehouse().getBlocks().forEach((id, positions) -> {
                for (BlockPos pos : List.copyOf(positions)) fixture.warehouse().removeBlock(Blocks.CHEST, pos);
            });
            for (int i = 0; i < 8; i++) fixture.warehouse().addBlock(Blocks.CHEST, distant.east(i * 2));
            helper.assertTrue(!helper.getLevel().isLoaded(distant), "distant fixture chunk was already loaded");
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), 200) == 0 && !helper.getLevel().isLoaded(distant),
                    "warehouse feeding loaded a distant chunk");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air", timeoutTicks = 100)
    public static void archerArrowsCanHitDeclaredEnemyVillagers(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper);
        Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200));
        fixture.village().getPolitics().setHostility(new VillagePolitics.HostilityRule(foreign.village().getId(), Rank.PEASANT, false, true));
        VillagerEntityMCA archer = fixture.soldier();
        archer.setVillagerData(archer.getVillagerData().withProfession(
                net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(ProfessionsMCA.ARCHER)));
        archer.setPos(Vec3.atBottomCenterOf(fixture.base().south(7)));
        VillagerEntityMCA target = foreign.soldier();
        target.setPos(archer.getX() + 5, archer.getY(), archer.getZ());
        target.tickCount = 2; // Preserve its assigned foreign membership during the short projectile fixture.
        target.setNoGravity(true);
        archer.setNoGravity(true);
        float health = target.getHealth();
        ItemStack bow = Items.BOW.getDefaultInstance();
        archer.setItemSlot(EquipmentSlot.MAINHAND, bow);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(archer, Items.ARROW.getDefaultInstance(), 1.0F, bow);
        arrow.setNoGravity(true);
        arrow.setPos(archer.getX(), target.getY() + target.getBbHeight() * 0.5D, archer.getZ());
        arrow.shoot(1.0D, 0.0D, 0.0D, 1.5F, 0.0F);
        helper.getLevel().addFreshEntity(arrow);
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(target.getHealth() < health, "friendly-arrow filtering made a declared enemy villager immune");
                helper.succeed();
            } finally { fixture.close(); foreign.close(); arrow.discard(); }
        });
    }

    private static GovernVillageMessage message(Fixture fixture, GovernVillageMessage.Action action, String target, int value, int flags) {
        return new GovernVillageMessage(fixture.village().getId(), action, target, value, flags, List.of());
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void breadMealsReachExactlyOneThousandWithoutExceedingTheAllowance(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.chest(0).setItem(0, new ItemStack(Items.BREAD, 64));
            fixture.civilian().getPhysicalHealth().set(992);
            for (int i = 0; i < PhysicalHealth.CYCLE_TICKS; i++) fixture.civilian().getPhysicalHealth().tick();
            helper.assertTrue(fixture.civilian().getPhysicalHealth().get() == 1000, "a whole final bread was rejected instead of clamping to 1000");
            helper.assertTrue(fixture.chest(0).getItem(0).getCount() == 60, "rounding ate more or less than four loaves");
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.EVERYONE, 10, List.of());
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), 1) == 0 && fixture.chest(0).getItem(0).getCount() == 60,
                    "the final-item exception bypassed the actual meal allowance");
            fixture.village().getPolitics().setFoodPolicy(VillagePolitics.FoodAccess.EVERYONE, 200, List.of());
            helper.assertTrue(FoodWarehouse.feed(fixture.civilian(), 1) == 11 && fixture.chest(0).getItem(0).getCount() == 59,
                    "a single point of remaining health could not consume one whole bread");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void appointmentsPersistAndOnlyOneMonarchCanReign(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            ServerPlayer successor = mockPlayer(helper, fixture.base());
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            helper.assertTrue(message(fixture, GovernVillageMessage.Action.APPOINT_RANK, successor.getUUID().toString(), Rank.PEASANT.ordinal(), 0).apply(fixture.player()),
                    "Monarch could not appoint a Peasant without reputation");
            helper.assertTrue(message(fixture, GovernVillageMessage.Action.GRANT_COMMANDER, successor.getUUID().toString(), 0, 0).apply(fixture.player()),
                    "Monarch could not appoint a Commander role");
            helper.assertTrue(Tasks.getRank(fixture.village(), successor) == Rank.PEASANT && fixture.village().getPolitics().isCommander(successor.getUUID()),
                    "Commander role changed the civil rank");
            Village loaded = new Village(fixture.village().save(), helper.getLevel());
            helper.assertTrue(Tasks.getRank(loaded, successor) == Rank.PEASANT && loaded.getPolitics().canCommand(successor), "Commander role did not persist independently of rank");
            helper.assertTrue(!message(fixture, GovernVillageMessage.Action.TRANSFER_CROWN, successor.getUUID().toString(), 0, 0).apply(fixture.player()),
                    "crown was transferred to a non-Mayor");
            message(fixture, GovernVillageMessage.Action.APPOINT_RANK, successor.getUUID().toString(), Rank.MAYOR.ordinal(), 0).apply(fixture.player());
            helper.assertTrue(message(fixture, GovernVillageMessage.Action.TRANSFER_CROWN, successor.getUUID().toString(), 0, 0).apply(fixture.player()),
                    "Monarch could not transfer ownership to an appointed Mayor");
            helper.assertTrue(Tasks.getRank(fixture.village(), successor) == Rank.MONARCH && Tasks.getRank(fixture.village(), fixture.player()) == Rank.MAYOR,
                    "ownership transfer left two Monarchs or lost the former ruler's Mayor rank");
            helper.assertTrue(!message(fixture, GovernVillageMessage.Action.FOOD_POLICY, "EVERYONE", 30, 0).apply(fixture.player()),
                    "former Monarch retained policy permissions");
            Village transferred = new Village(fixture.village().save(), helper.getLevel());
            helper.assertTrue(Tasks.getRank(transferred, successor) == Rank.MONARCH && Tasks.getRank(transferred, fixture.player()) == Rank.MAYOR,
                    "saving lost the ownership transfer");
            successor.discard();
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void oldSavesWithSeveralMonarchsAreReconciled(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            CompoundTag data = fixture.village().save();
            CompoundTag politics = data.getCompound("politics").orElseThrow();
            CompoundTag ranks = new CompoundTag();
            ranks.putString(first.toString(), "MONARCH"); ranks.putString(second.toString(), "MONARCH");
            politics.put("forcedRanks", ranks); politics.remove("monarch");
            Village loaded = new Village(data, helper.getLevel());
            long monarchs = java.util.stream.Stream.of(first, second).filter(id -> loaded.getPolitics().getForcedRank(id).orElseThrow() == Rank.MONARCH).count();
            helper.assertTrue(monarchs == 1 && loaded.getPolitics().getMonarch().isPresent(), "legacy multiple-Monarch state was not reconciled");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void commanderCanOrderOwnLoadedSoldiersRemotelyButCannotGovern(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            fixture.player().setPos(Vec3.atBottomCenterOf(foreign.base()));
            helper.assertTrue(command(fixture, CommandSoldiersMessage.Action.STAY, BlockPos.ZERO, "", 0).apply(fixture.player()), "Commander could not issue a remote stay order");
            helper.assertTrue(fixture.soldier().getVillagerBrain().getMoveState() == MoveState.STAY, "stay order did not change the actual brain state");
            helper.assertTrue(!new CommandSoldiersMessage(foreign.village().getId(), CommandSoldiersMessage.Action.FOLLOW,
                    List.of(foreign.soldier().getUUID()), BlockPos.ZERO, "", 0).apply(fixture.player()), "Commander could control a foreign soldier");
            helper.assertTrue(!message(fixture, GovernVillageMessage.Action.FOOD_POLICY, "EVERYONE", 100, 0).apply(fixture.player()), "Commander acquired Monarch policy privileges");
            helper.assertTrue(!command(fixture, CommandSoldiersMessage.Action.DITCH_DISTANCE, BlockPos.ZERO, "", 3).apply(fixture.player()), "invalid ditch distance was accepted");
            helper.assertTrue(!new CommandSoldiersMessage(fixture.village().getId(), CommandSoldiersMessage.Action.FREE,
                    List.of(fixture.soldier().getUUID(), foreign.soldier().getUUID()), BlockPos.ZERO, "", 0).apply(fixture.player()), "mixed-village selection partially applied");
            fixture.village().getPolitics().revokeCommander(fixture.player().getUUID());
            helper.assertTrue(!command(fixture, CommandSoldiersMessage.Action.FREE, BlockPos.ZERO, "", 0).apply(fixture.player()), "dismissed Commander retained army access");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void soldierMovesToCoordinatesThenHoldsAndRestoresOrdersAfterSaving(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            VillagerEntityMCA soldier = fixture.soldier();
            BlockPos position = fixture.base().south(9).east(4);
            helper.assertTrue(command(fixture, CommandSoldiersMessage.Action.MOVE_TO, position, "", 0).apply(fixture.player()), "move order was rejected");
            helper.assertTrue(soldier.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().getTarget().currentBlockPosition().equals(position),
                    "move order did not create a real pathfinding walk target");
            CompoundTag saved = new CompoundTag(); soldier.getSoldierOrders().save(saved);
            soldier.getSoldierOrders().movement(fixture.player(), MoveState.MOVE);
            soldier.getSoldierOrders().load(saved);
            helper.assertTrue(soldier.getSoldierOrders().getDestination().orElseThrow().equals(position), "saving lost the destination");
            soldier.setPos(Vec3.atBottomCenterOf(position)); soldier.getSoldierOrders().tick();
            helper.assertTrue(soldier.getVillagerBrain().getMoveState() == MoveState.STAY && soldier.getSoldierOrders().getDestination().isEmpty(), "soldier did not hold after arriving");
            soldier.setPos(Vec3.atBottomCenterOf(position.east(5))); soldier.getSoldierOrders().tick();
            helper.assertTrue(soldier.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow().getTarget().currentBlockPosition().equals(position), "soldier abandoned its hold position after displacement");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void followingSoldierAssistsLeaderAndDitchesTargetsAtConfiguredDistance(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            command(fixture, CommandSoldiersMessage.Action.FOLLOW, BlockPos.ZERO, "", 0).apply(fixture.player());
            command(fixture, CommandSoldiersMessage.Action.DITCH_DISTANCE, BlockPos.ZERO, "", 8).apply(fixture.player());
            var mob = net.minecraft.world.entity.EntityTypes.COW.create(helper.getLevel(), EntitySpawnReason.STRUCTURE);
            Objects.requireNonNull(mob).setPos(fixture.player().position().add(2, 0, 0));
            helper.getLevel().addFreshEntity(mob);
            fixture.player().tickCount = 1; fixture.player().setLastHurtMob(mob);
            fixture.soldier().getSoldierOrders().tick();
            helper.assertTrue(fixture.soldier().getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == mob, "following soldier did not assist against a normally neutral mob attacked by its leader");
            fixture.soldier().setPos(fixture.player().position().add(9, 0, 0));
            fixture.soldier().getSoldierOrders().tick();
            helper.assertTrue(!fixture.soldier().getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                    && !GuardEnemiesSensor.isGuardEnemy(mob, fixture.soldier()), "soldier retained or reacquired the ditched target");
            helper.assertTrue(fixture.soldier().getBrain().getMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING).orElse(null) == fixture.player(), "ditching lost the follow order");
            mob.discard(); helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void attackOrdersQueueTargetsAndIgnoreCreativePlayers(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            foreign.soldier().setPos(fixture.soldier().position().add(5, 0, 0));
            foreign.civilian().setPos(fixture.soldier().position().add(6, 0, 0));
            helper.assertTrue(command(fixture, CommandSoldiersMessage.Action.ATTACK, BlockPos.ZERO,
                    foreign.soldier().getUUID() + "," + foreign.civilian().getUUID(), 0).apply(fixture.player()), "dragged multi-target attack order was rejected");
            foreign.soldier().discard();
            fixture.soldier().getSoldierOrders().tick(); fixture.soldier().getSoldierOrders().tick();
            helper.assertTrue(fixture.soldier().getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == foreign.civilian(), "soldier did not advance to the next queued target");
            helper.assertTrue(GuardEnemiesSensor.isGuardEnemy(foreign.civilian(), fixture.soldier()), "sensor rejected a commanded civilian target");
            helper.assertTrue(!command(fixture, CommandSoldiersMessage.Action.ATTACK, BlockPos.ZERO, fixture.soldier().getUUID().toString(), 0).apply(fixture.player()), "soldier could target itself");
            ((TestPlayer) foreign.player()).mode = GameType.CREATIVE;
            helper.assertTrue(!fixture.soldier().getSoldierOrders().validTarget(foreign.player()), "manual attack orders bypassed Creative immunity");
            ((TestPlayer) foreign.player()).mode = GameType.SPECTATOR;
            helper.assertTrue(!fixture.soldier().getSoldierOrders().validTarget(foreign.player()), "manual attack orders bypassed Spectator immunity");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void defaultEquipmentToggleRequiresNearbyHomeOfficer(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            var equipment = fixture.soldier().getSoldierEquipment();
            helper.assertTrue(equipment.usesDefaultEquipment(), "existing soldiers did not default to generated gear");
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MAYOR);
            helper.assertTrue(!equipment.toggleDefaultEquipment(fixture.player()), "Mayor without army authority disabled gear");
            foreign.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            helper.assertTrue(!equipment.toggleDefaultEquipment(fixture.player()), "foreign Monarch disabled another village's gear");
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            fixture.player().setPos(fixture.soldier().position().add(9, 0, 0));
            helper.assertTrue(!equipment.toggleDefaultEquipment(fixture.player()), "remote Commander disabled gear outside interaction range");
            fixture.player().setPos(fixture.soldier().position().add(2, 0, 0));
            helper.assertTrue(Constraint.COMMAND_SOLDIER.test(fixture.soldier(), fixture.player())
                    && Constraint.DEFAULT_EQUIPMENT.test(fixture.soldier(), fixture.player()), "authorized button constraints were missing");
            helper.assertTrue(equipment.toggleDefaultEquipment(fixture.player()) && !equipment.usesDefaultEquipment()
                    && !Constraint.DEFAULT_EQUIPMENT.test(fixture.soldier(), fixture.player()), "Commander could not disable defaults and refresh button state");
            helper.assertTrue(!fixture.civilian().getSoldierEquipment().toggleDefaultEquipment(fixture.player()), "officer could change a civilian's equipment policy");
            fixture.village().getPolitics().revokeCommander(fixture.player().getUUID());
            helper.assertTrue(!equipment.toggleDefaultEquipment(fixture.player()), "dismissed Commander retained equipment authority");
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            helper.assertTrue(equipment.toggleDefaultEquipment(fixture.player()) && equipment.usesDefaultEquipment(), "home Monarch could not restore defaults");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void disabledDefaultEquipmentStaysEmptyAndCanBeRestored(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            VillagerEntityMCA soldier = fixture.soldier();
            EquipmentTask task = new EquipmentTask(ignored -> true, ignored -> EquipmentSet.GUARD_2);
            long time = helper.getLevel().getGameTime();
            helper.assertTrue(task.tryStart(helper.getLevel(), soldier, time)
                    && soldier.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "fixture did not generate default combat gear");
            task.tickOrStop(helper.getLevel(), soldier, ++time);
            helper.assertTrue(soldier.getSoldierEquipment().toggleDefaultEquipment(fixture.player()), "Monarch toggle was rejected");
            for (EquipmentSlot slot : SoldierEquipment.SLOTS) helper.assertTrue(soldier.getItemBySlot(slot).isEmpty(), "disabling left generated gear in " + slot);
            soldier.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, fixture.civilian());
            task.tryStart(helper.getLevel(), soldier, ++time);
            task.tickOrStop(helper.getLevel(), soldier, ++time);
            for (int tick = 0; tick < 80; tick++) {
                soldier.tickCount++;
                soldier.getVillagerBrain().setArmorWear(tick % 2 == 0);
                helper.assertTrue(!task.tryStart(helper.getLevel(), soldier, ++time), "intentionally empty slots repeatedly restarted equipment AI");
                for (EquipmentSlot slot : SoldierEquipment.SLOTS) helper.assertTrue(soldier.getItemBySlot(slot).isEmpty(), "combat or armor visibility restored disabled defaults");
            }
            soldier.setItemSlot(soldier.getDominantSlot(), Items.IRON_SWORD.getDefaultInstance());
            helper.assertTrue(task.tryStart(helper.getLevel(), soldier, ++time)
                    && soldier.getItemBySlot(soldier.getDominantSlot()).isEmpty(), "stale generated hand item was not removed");
            task.tickOrStop(helper.getLevel(), soldier, ++time);
            helper.assertTrue(soldier.getSoldierEquipment().toggleDefaultEquipment(fixture.player()), "Monarch could not re-enable gear");
            helper.assertTrue(task.tryStart(helper.getLevel(), soldier, ++time)
                    && !soldier.getItemBySlot(soldier.getDominantSlot()).isEmpty()
                    && !soldier.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "re-enabling did not restore normal generated equipment");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void disabledDefaultsUseSuppliedGearWithoutCopies(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            VillagerEntityMCA soldier = fixture.soldier();
            var equipment = soldier.getSoldierEquipment();
            helper.assertTrue(equipment.toggleDefaultEquipment(fixture.player()), "Monarch toggle was rejected");
            EquipmentTask task = new EquipmentTask(ignored -> true, ignored -> EquipmentSet.GUARD_2);
            long time = helper.getLevel().getGameTime();
            ItemStack sword = Items.DIAMOND_SWORD.getDefaultInstance(); sword.setDamageValue(17);
            ItemStack chest = Items.IRON_CHESTPLATE.getDefaultInstance();
            soldier.getInventory().setItem(0, sword);
            soldier.getInventory().setItem(1, chest);
            helper.assertTrue(task.tryStart(helper.getLevel(), soldier, time)
                    && soldier.getItemBySlot(soldier.getDominantSlot()) == sword
                    && soldier.getItemBySlot(EquipmentSlot.CHEST) == chest, "supplies were ignored or duplicated with defaults off");
            task.tickOrStop(helper.getLevel(), soldier, ++time);
            ItemStack explicit = Items.DIAMOND_CHESTPLATE.getDefaultInstance(); explicit.setDamageValue(9);
            equipment.getInventory().setItem(3, explicit);
            helper.assertTrue(soldier.getItemBySlot(EquipmentSlot.CHEST) == explicit, "explicit armor did not override supplies");
            TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, soldier.registryAccess());
            soldier.addAdditionalSaveData(saved);
            VillagerEntityMCA restored = VillagerFactory.newVillager(helper.getLevel()).withAge(0).withPosition(soldier.position()).build();
            restored.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, restored.registryAccess(), saved.buildResult()));
            helper.assertTrue(!restored.getSoldierEquipment().usesDefaultEquipment()
                    && restored.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() == 9
                    && restored.getItemBySlot(restored.getDominantSlot()).getDamageValue() == 17
                    && restored.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "reload lost the disabled policy or supplied gear durability");
            equipment.getInventory().setItem(3, ItemStack.EMPTY);
            helper.assertTrue(soldier.getItemBySlot(EquipmentSlot.CHEST) == chest, "removing explicit armor did not fall back to actual supplies");
            soldier.getInventory().setItem(0, ItemStack.EMPTY); soldier.getInventory().setItem(1, ItemStack.EMPTY);
            helper.assertTrue(task.tryStart(helper.getLevel(), soldier, ++time), "removing supplies did not refresh equipment");
            for (EquipmentSlot slot : SoldierEquipment.SLOTS) helper.assertTrue(soldier.getItemBySlot(slot).isEmpty(), "removed gear remained equipped or regenerated");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air", timeoutTicks = 180)
    public static void liveArcherWithDisabledDefaultsFightsBarehanded(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper);
        fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
        VillagerEntityMCA soldier = fixture.soldier();
        soldier.setProfession(ProfessionsMCA.ARCHER);
        helper.assertTrue(soldier.getSoldierEquipment().toggleDefaultEquipment(fixture.player()), "Monarch toggle was rejected");
        soldier.tickCount = 2; soldier.setNoAi(false);
        var target = Objects.requireNonNull(net.minecraft.world.entity.EntityTypes.COW.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
        target.setPos(soldier.position().add(5, 0, 0)); target.setNoAi(true);
        helper.getLevel().addFreshEntity(target);
        helper.assertTrue(command(fixture, CommandSoldiersMessage.Action.ATTACK, BlockPos.ZERO, target.getUUID().toString(), 0).apply(fixture.player()), "barehanded archer attack order was rejected");
        helper.runAfterDelay(120, () -> {
            try {
                helper.assertTrue(target.getHealth() < target.getMaxHealth(), "archer without a supplied bow failed to use melee combat");
                for (EquipmentSlot slot : SoldierEquipment.SLOTS) helper.assertTrue(soldier.getItemBySlot(slot).isEmpty(), "live combat regenerated disabled equipment in " + slot);
                helper.succeed();
            } finally { target.discard(); fixture.close(); }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void suppliedEquipmentPersistsAndGeneratedArmorCannotBeWithdrawn(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            VillagerEntityMCA soldier = fixture.soldier();
            soldier.setItemSlot(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE.getDefaultInstance());
            SoldierInventoryMenu menu = new SoldierInventoryMenu(1, fixture.player().getInventory(), soldier);
            helper.assertTrue(menu.getSlot(30).getItem().isEmpty(), "generated armor leaked into a withdrawable equipment slot");
            ItemStack armor = Items.DIAMOND_CHESTPLATE.getDefaultInstance(); armor.setDamageValue(7);
            menu.getSlot(30).setByPlayer(armor);
            helper.assertTrue(soldier.getItemBySlot(EquipmentSlot.CHEST) == soldier.getSoldierEquipment().getInventory().getItem(3), "equipped armor was copied instead of sharing the real inventory item");
            helper.assertTrue(!menu.getSlot(30).mayPlace(Items.BREAD.getDefaultInstance()) && menu.getSlot(30).getMaxStackSize() == 1, "equipment slot accepted food or stacked armor");
            TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, soldier.registryAccess());
            soldier.addAdditionalSaveData(saved);
            VillagerEntityMCA restored = VillagerFactory.newVillager(helper.getLevel()).withAge(0).withPosition(soldier.position()).build();
            restored.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, restored.registryAccess(), saved.buildResult()));
            helper.assertTrue(restored.getSoldierEquipment().getInventory().getItem(3).is(Items.DIAMOND_CHESTPLATE)
                    && restored.getSoldierEquipment().getInventory().getItem(3).getDamageValue() == 7, "saving lost player equipment or its durability");
            menu.getSlot(30).setByPlayer(ItemStack.EMPTY);
            helper.assertTrue(soldier.getSoldierEquipment().getInventory().getItem(3).isEmpty(), "removing armor did not remove the owned item");
            helper.assertTrue(menu.stillValid(fixture.player()), "Commander could not use the soldier inventory");
            fixture.village().getPolitics().revokeCommander(fixture.player().getUUID());
            helper.assertTrue(!menu.stillValid(fixture.player()), "role dismissal left an open equipment menu authorized");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void loadedArmySnapshotAndVillageLabelsReflectMembership(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            fixture.soldier().tickCount = 20; fixture.soldier().getResidency().tick();
            helper.assertTrue(fixture.soldier().getResidency().getVillageName().equals(fixture.village().getName()), "village name was not synchronized for interactions and nameplates");
            fixture.village().setName("Renamed village"); fixture.soldier().getResidency().tick();
            helper.assertTrue(fixture.soldier().getResidency().getVillageName().equals("Renamed village"), "renaming left the old nameplate label");
            CompoundTag overview = ArmyOverviewRequest.snapshot(fixture.player(), fixture.village());
            helper.assertTrue(overview.getBoolean("canCommand").orElse(false) && overview.getList("soldiers").orElseThrow().size() == 1,
                    "army overview omitted the loaded soldier or included a civilian");
            fixture.soldier().discard();
            helper.assertTrue(ArmyOverviewRequest.snapshot(fixture.player(), fixture.village()).getList("soldiers").orElseThrow().isEmpty(), "overview retained an unloaded soldier");
            helper.succeed();
        }
    }

    private static CommandSoldiersMessage command(Fixture fixture, CommandSoldiersMessage.Action action, BlockPos position, String target, int value) {
        return new CommandSoldiersMessage(fixture.village().getId(), action, List.of(fixture.soldier().getUUID()), position, target, value);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air", timeoutTicks = 180)
    public static void liveSoldierPathfindsToOrderedPositionAndStaysThere(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper);
        fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
        fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
        VillagerEntityMCA soldier = fixture.soldier();
        soldier.tickCount = 2;
        soldier.setNoAi(false);
        BlockPos destination = fixture.base().south(7).east(5);
        helper.assertTrue(command(fixture, CommandSoldiersMessage.Action.MOVE_TO, destination, "", 0).apply(fixture.player()), "live move order was rejected");
        helper.runAfterDelay(120, () -> {
            try {
                helper.assertTrue(soldier.blockPosition().distSqr(destination) <= 2.25 && soldier.getVillagerBrain().getMoveState() == MoveState.STAY,
                        "actual soldier AI failed to reach and hold its destination: " + soldier.blockPosition() + " state=" + soldier.getVillagerBrain().getMoveState());
                helper.succeed();
            } finally { fixture.close(); }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air", timeoutTicks = 180)
    public static void liveSoldierPursuesAndKillsCommandedNeutralMob(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper);
        fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
        fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
        VillagerEntityMCA soldier = fixture.soldier();
        soldier.tickCount = 2;
        soldier.setNoAi(false);
        var target = Objects.requireNonNull(net.minecraft.world.entity.EntityTypes.COW.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
        target.setPos(soldier.position().add(5, 0, 0)); target.setNoAi(true);
        helper.getLevel().addFreshEntity(target);
        helper.assertTrue(command(fixture, CommandSoldiersMessage.Action.ATTACK, BlockPos.ZERO, target.getUUID().toString(), 0).apply(fixture.player()), "live attack order was rejected");
        helper.runAfterDelay(120, () -> {
            try {
                helper.assertTrue(!target.isAlive(), "actual soldier AI did not pursue and kill the commanded neutral mob; health=" + target.getHealth());
                helper.succeed();
            } finally { target.discard(); fixture.close(); }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void soldierEquipmentAndOrdersSurviveZombieConversion(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            helper.assertTrue(fixture.soldier().getSoldierEquipment().toggleDefaultEquipment(fixture.player()), "could not disable equipment before conversion");
            fixture.soldier().getSoldierEquipment().getInventory().setItem(3, Items.DIAMOND_CHESTPLATE.getDefaultInstance());
            fixture.soldier().getSoldierOrders().goTo(fixture.player(), fixture.base().east(8));
            ZombieVillagerEntityMCA zombie = Objects.requireNonNull(EntitiesMCA.MALE_ZOMBIE_VILLAGER.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
            zombie.copyVillagerAttributesFrom(fixture.soldier());
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, zombie.registryAccess());
            zombie.addAdditionalSaveData(output);
            ZombieVillagerEntityMCA loaded = Objects.requireNonNull(EntitiesMCA.MALE_ZOMBIE_VILLAGER.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
            loaded.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, loaded.registryAccess(), output.buildResult()));
            loaded.getItemBySlot(EquipmentSlot.CHEST).setDamageValue(23);
            VillagerEntityMCA cured = VillagerFactory.newVillager(helper.getLevel()).withAge(0).withPosition(fixture.soldier().position()).build();
            cured.copyVillagerAttributesFrom(loaded);
            helper.assertTrue(cured.getSoldierEquipment().getInventory().getItem(3).is(Items.DIAMOND_CHESTPLATE)
                    && cured.getSoldierEquipment().getInventory().getItem(3).getDamageValue() == 23
                    && cured.getSoldierOrders().getDestination().orElseThrow().equals(fixture.base().east(8)), "conversion lost player gear, durability or orders");
            helper.assertTrue(!loaded.getTrackedValue(SoldierEquipment.USE_DEFAULT_EQUIPMENT)
                    && !cured.getSoldierEquipment().usesDefaultEquipment()
                    && cured.getItemBySlot(cured.getDominantSlot()).isEmpty()
                    && cured.getItemBySlot(EquipmentSlot.LEGS).isEmpty(), "zombification or curing restored disabled default equipment");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void zombieDropsOwnedGearExactlyOnce(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.soldier().getSoldierEquipment().getInventory().setItem(3, Items.DIAMOND_CHESTPLATE.getDefaultInstance());
            ZombieVillagerEntityMCA zombie = Objects.requireNonNull(EntitiesMCA.MALE_ZOMBIE_VILLAGER.create(helper.getLevel(), EntitySpawnReason.STRUCTURE));
            zombie.copyVillagerAttributesFrom(fixture.soldier());
            zombie.setPos(Vec3.atBottomCenterOf(fixture.base().south(8).east(5)));
            zombie.setNoAi(true);
            // Force vanilla's equipment drop path to expose any duplicate inherited armor.
            zombie.setDropChance(EquipmentSlot.CHEST, 2.0F);
            helper.getLevel().addFreshEntity(zombie);
            zombie.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 1000);
            var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, zombie.getBoundingBox().inflate(4),
                    item -> item.getItem().is(Items.DIAMOND_CHESTPLATE));
            int count = drops.stream().mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(count == 1, "zombification produced " + count + " copies of one owned chestplate");
            drops.forEach(net.minecraft.world.entity.Entity::discard); zombie.discard();
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void commanderRoleRequiresEligibleRankAndLeavesCivilProgressionIntact(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            ServerPlayer officer = mockPlayer(helper, fixture.base());
            try {
                fixture.village().getPolitics().forceRank(officer, Rank.OUTLAW);
                helper.assertTrue(!message(fixture, GovernVillageMessage.Action.GRANT_COMMANDER, officer.getUUID().toString(), 0, 0).apply(fixture.player()),
                        "an Outlaw could be promoted to Commander");
                fixture.village().getPolitics().forceRank(officer, Rank.NOBLE);
                helper.assertTrue(message(fixture, GovernVillageMessage.Action.GRANT_COMMANDER, officer.getUUID().toString(), 0, 0).apply(fixture.player()),
                        "eligible player could not receive the Commander role");
                helper.assertTrue(Tasks.getRank(fixture.village(), officer) == Rank.NOBLE && fixture.village().getPolitics().canCommand(officer),
                        "Commander role replaced the civil rank or did not grant army access");
                fixture.village().getPolitics().forceRank(officer, Rank.PEASANT);
                helper.assertTrue(fixture.village().getPolitics().canCommand(officer), "changing an eligible civil rank removed the independent Commander role");
                helper.assertTrue(GetVillageRequest.canViewRemotely(fixture.village(), officer), "Peasant Commander lost remote village access");
                helper.assertTrue(!message(fixture, GovernVillageMessage.Action.GRANT_COMMANDER, UUID.randomUUID().toString(), 0, 0).apply(officer),
                        "Commander could appoint another Commander");
                fixture.village().getPolitics().banPlayer(officer.getUUID(), officer.getName().getString());
                helper.assertTrue(!fixture.village().getPolitics().isCommander(officer.getUUID()) && !fixture.village().getPolitics().canCommand(officer),
                        "banning an officer retained their military role");
                helper.succeed();
            } finally { officer.discard(); }
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void legacyCommanderRankMigratesToIndependentRole(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            CompoundTag saved = fixture.village().save();
            CompoundTag politics = saved.getCompound("politics").orElseThrow();
            CompoundTag ranks = new CompoundTag();
            ranks.putString(fixture.player().getUUID().toString(), "COMMANDER");
            politics.put("forcedRanks", ranks);
            Village loaded = new Village(saved, helper.getLevel());
            helper.assertTrue(loaded.getPolitics().isCommander(fixture.player().getUUID())
                    && loaded.getPolitics().getForcedRank(fixture.player().getUUID()).isEmpty(), "legacy Commander remained a forced civil rank");
            Rank earned = Tasks.getRank(loaded, fixture.player());
            helper.assertTrue(earned.isAtLeast(Rank.PEASANT) && loaded.getPolitics().canCommand(fixture.player()), "migration lost army authority or civil progression");
            Village reloaded = new Village(loaded.save(), helper.getLevel());
            helper.assertTrue(reloaded.getPolitics().isCommander(fixture.player().getUUID()) && Tasks.getRank(reloaded, fixture.player()) == earned,
                    "migrated military role did not survive another save/load");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void peasantCommanderCannotChangeCivilSettings(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            String originalName = fixture.village().getName();
            float taxes = fixture.village().getTaxes();
            new net.conczin.mca.network.c2s.RenameVillageMessage(fixture.village().getId(), "Unauthorized rename").handleServer(fixture.player());
            new net.conczin.mca.network.c2s.SaveVillageMessage(fixture.village().getId(), taxes == 0 ? 1 : 0, 0, 0).handleServer(fixture.player());
            helper.assertTrue(originalName.equals(fixture.village().getName()) && fixture.village().getTaxes() == taxes,
                    "Commander role granted Mayor's civil settings permissions");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void dismissingCommanderCancelsOutstandingOrders(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            fixture.soldier().getSoldierOrders().goTo(fixture.player(), fixture.base().south(9).east(5));
            helper.assertTrue(fixture.soldier().getSoldierOrders().hasDestination(), "fixture had no pending move order");
            fixture.village().getPolitics().revokeCommander(fixture.player().getUUID());
            fixture.soldier().tickCount = 20;
            fixture.soldier().getSoldierOrders().tick();
            helper.assertTrue(!fixture.soldier().getSoldierOrders().hasDestination()
                    && fixture.soldier().getVillagerBrain().getMoveState() == MoveState.STAY, "dismissed officer's orders kept running");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void commandShortcutPrefersAuthorityOverCurrentGeographicVillage(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper); Fixture foreign = fixture(helper, fixtureOrigin(helper).east(200))) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.PEASANT);
            fixture.village().getPolitics().grantCommander(fixture.player().getUUID(), fixture.player().getName().getString(), fixture.player());
            fixture.player().setPos(Vec3.atBottomCenterOf(foreign.base()));
            helper.assertTrue(GetVillageRequest.resolveVillage(fixture.player(), -1, true).orElseThrow().getId() == fixture.village().getId(),
                    "left-click shortcut opened the foreign village's army instead of the commanded village");
            helper.assertTrue(GetVillageRequest.resolveVillage(fixture.player(), -1, false).orElseThrow().getId() == foreign.village().getId(),
                    "ordinary blueprint no longer opened the geographic village");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void qualifiedSecondPlayerCannotReplaceExistingMonarch(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            ServerPlayer candidate = mockPlayer(helper, fixture.base());
            var tasks = Tasks.getInstance().tasks;
            var monarchRequirements = tasks.put(Rank.MONARCH, List.of());
            var mayorRequirements = tasks.put(Rank.MAYOR, List.of());
            try {
                helper.assertTrue(Tasks.getRank(fixture.village(), candidate) == Rank.MAYOR,
                        "a qualified second player displaced the Monarch instead of receiving the highest lower eligible rank");
                helper.assertTrue(fixture.village().getPolitics().getMonarch().orElseThrow().equals(fixture.player().getUUID())
                        && Tasks.getRank(fixture.village(), fixture.player()) == Rank.MONARCH, "the existing owner's throne changed");
                helper.succeed();
            } finally {
                tasks.put(Rank.MONARCH, monarchRequirements);
                tasks.put(Rank.MAYOR, mayorRequirements);
                candidate.discard();
            }
        }
    }

    private static void close(GameTestHelper helper, double actual, double expected, String description) {
        helper.assertTrue(Math.abs(actual - expected) < 0.0001D, description + ": expected " + expected + ", got " + actual);
    }

    private static BlockPos fixtureOrigin(GameTestHelper helper) { return helper.absolutePos(new BlockPos(12, 4, 12)); }
    private static Fixture fixture(GameTestHelper helper) throws Exception { return fixture(helper, fixtureOrigin(helper)); }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(GameTestHelper helper, BlockPos base) throws Exception {
        prepareFlatArea(helper, base, 10, 5);
        int id = NEXT_ID.incrementAndGet();
        Village village = new Village(id, helper.getLevel());
        village.setName("Governance fixture " + id);
        List<BlockPos> chests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BlockPos pos = base.offset((i % 4) * 2, 0, (i / 4) * 2);
            helper.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
            chests.add(pos);
        }
        Set<FloorGeometry.Cell> cells = new HashSet<>();
        for (int x = -2; x <= 8; x++) {
            for (int z = -2; z <= 8; z++) cells.add(new FloorGeometry.Cell(base.offset(x, 0, z), base.getY() + 4));
        }
        Structure structure = new Structure(id, base, List.of(new StructureFloor(0, 0, new FloorGeometry(cells, Map.of()))));
        structure.setLogicalBuildingId(id);
        Building warehouse = new Building(base);
        warehouse.setId(id);
        warehouse.setStructureId(id);
        warehouse.setFloorId(0);
        warehouse.setGeometry(base.offset(-2, 0, -2), base.offset(8, 4, 8),
                cells.stream().map(FloorGeometry.Cell::feet).collect(java.util.stream.Collectors.toSet()));
        warehouse.setType(FoodWarehouse.TYPE);
        warehouse.setTypeForced(true);
        // Reverse insertion order to prove consumption sorts physical chest positions.
        chests.reversed().forEach(pos -> warehouse.addBlock(Blocks.CHEST, pos));
        village.registerStructure(structure, warehouse);
        village.calculateDimensions();
        Field villagesField = VillageManager.class.getDeclaredField("villages");
        villagesField.setAccessible(true);
        ((Map<Integer, Village>) villagesField.get(VillageManager.get(helper.getLevel()))).put(id, village);
        ServerPlayer player = mockPlayer(helper, base.south(5));
        VillagerEntityMCA civilian = spawn(helper, village, base.south(6), null);
        VillagerEntityMCA soldier = spawn(helper, village, base.south(7), ProfessionsMCA.GUARD);
        return new Fixture(helper, village, warehouse, player, civilian, soldier, base, List.copyOf(chests));
    }

    private static ServerPlayer mockPlayer(GameTestHelper helper, BlockPos position) {
        ServerPlayer player = new TestPlayer(helper);
        player.setPos(Vec3.atBottomCenterOf(position));
        return player;
    }

    private static final class TestSensor extends GuardEnemiesSensor {
        void scan(ServerLevel level, VillagerEntityMCA soldier) { super.doTick(level, soldier); }
    }

    /** A server-side actor without a network login or an unnegotiated client connection. */
    private static final class TestPlayer extends ServerPlayer {
        private GameType mode = GameType.SURVIVAL;

        TestPlayer(GameTestHelper helper) {
            super(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "governance-test"), ClientInformation.createDefault());
        }

        @Override public GameType gameMode() { return mode == null ? GameType.SURVIVAL : mode; }
        @Override public void sendSystemMessage(Component message) { }
        @Override public void sendSystemMessage(Component message, boolean overlay) { }
    }

    private static VillagerEntityMCA spawn(GameTestHelper helper, Village village, BlockPos pos, VillagerProfession profession) {
        VillagerEntityMCA npc = VillagerFactory.newVillager(helper.getLevel()).withAge(0).withProfession(profession)
                .withPosition(Vec3.atBottomCenterOf(pos)).spawn(EntitySpawnReason.STRUCTURE);
        npc.setNoAi(true);
        npc.setOnGround(true);
        CompoundTag data = new CompoundTag();
        npc.getTypeDataManager().save(npc, data);
        data.putInt("HomeVillage", village.getId());
        npc.getTypeDataManager().load(npc, data);
        village.updateResident(npc);
        return npc;
    }

    private record Fixture(GameTestHelper helper, Village village, Building warehouse, ServerPlayer player,
                           VillagerEntityMCA civilian, VillagerEntityMCA soldier, BlockPos base,
                           List<BlockPos> chests) implements AutoCloseable {
        Container chest(int index) { return (Container) helper.getLevel().getBlockEntity(chests.get(index)); }
        @Override public void close() {
            civilian.discard();
            soldier.discard();
            player.discard();
            VillageManager.get(helper.getLevel()).removeVillage(village.getId());
        }
    }
}
