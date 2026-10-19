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
    public static void governanceRejectsForgedRemoteAndInvalidPolicyChanges(GameTestHelper helper) throws Exception {
        try (Fixture fixture = fixture(helper)) {
            GovernVillageMessage ban = message(fixture, GovernVillageMessage.Action.BAN_PLAYER, UUID.randomUUID().toString(), 0, 0);
            helper.assertTrue(!ban.apply(fixture.player()), "non-Monarch forged a village ban");
            fixture.village().getPolitics().forceRank(fixture.player(), Rank.MONARCH);
            fixture.player().setPos(Vec3.atBottomCenterOf(fixture.base().east(200)));
            helper.assertTrue(!ban.apply(fixture.player()), "Monarch changed a village remotely");
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
