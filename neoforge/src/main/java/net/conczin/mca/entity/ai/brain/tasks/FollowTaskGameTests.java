package net.conczin.mca.entity.ai.brain.tasks;

import net.conczin.mca.Config;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.VillagerFactory;
import net.conczin.mca.entity.ai.MemoryModuleTypeMCA;
import net.conczin.mca.entity.ai.MoveState;
import net.conczin.mca.entity.ai.brain.WalkTargetFailureMemory;
import net.conczin.mca.entity.ai.relationship.Gender;
import net.conczin.mca.neoforge.gametest.GameTest;
import net.conczin.mca.neoforge.gametest.PrefixGameTestTemplate;
import net.conczin.mca.registry.ProfessionsMCA;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.conczin.mca.gametest.GameTestTerrain.prepareFlatArea;

@PrefixGameTestTemplate(false)
public final class FollowTaskGameTests {
    private FollowTaskGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void followersWalkNearbyAndTeleportAtTwelveBlocks(GameTestHelper helper) {
        boolean previousTeleport = Config.getInstance().allowVillagerTeleporting;
        Config.getInstance().allowVillagerTeleporting = false;
        try {
            for (Gender gender : new Gender[]{Gender.MALE, Gender.FEMALE}) {
                try (Fixture fixture = createFixture(helper, gender)) {
                    VillagerEntityMCA villager = fixture.villager();
                    Player player = fixture.player();
                    FollowTask task = new FollowTask();
                    Path oldPath = villager.getNavigation().createPath(villager.blockPosition().north(2), 0);
                    helper.assertTrue(oldPath != null, "fixture could not create an old walking path");
                    villager.getNavigation().moveTo(oldPath, 0.6D);
                    villager.getBrain().setMemory(MemoryModuleType.PATH, oldPath);
                    villager.setPos(player.getX() - 11.99D, player.getY(), player.getZ());
                    Vec3 nearbyPosition = villager.position();
                    task.tick(helper.getLevel(), villager, 0L);
                    helper.assertTrue(villager.position().equals(nearbyPosition),
                            "nearby follower teleported instead of walking");
                    helper.assertTrue(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                            "nearby follower did not receive a walking target");

                    villager.setPos(player.getX() - 12.0D, player.getY(), player.getZ());
                    WalkTargetFailureMemory.record(villager, player.blockPosition(), 0L);
                    villager.getRandom().setSeed(1L);
                    task.tick(helper.getLevel(), villager, 1L);
                    assertCaughtUp(helper, fixture);
                    helper.assertTrue(villager.getNavigation().isDone()
                                    && !villager.getBrain().hasMemoryValue(MemoryModuleType.PATH),
                            "teleported follower kept its old navigation");
                    helper.assertTrue(!villager.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                                    && !villager.getBrain().hasMemoryValue(MemoryModuleTypeMCA.CANT_REACH_WALK_TARGET),
                            "teleported follower kept stale path failure memories");
                    helper.assertTrue(villager.getBrain().hasMemoryValue(MemoryModuleTypeMCA.PLAYER_FOLLOWING),
                            "catching up cancelled Follow Me");
                }
            }
            helper.succeed();
        } finally {
            Config.getInstance().allowVillagerTeleporting = previousTeleport;
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void followCommandTeleportsWithoutAWorkingPathAndStayCancelsIt(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, Gender.FEMALE);
        VillagerEntityMCA villager = fixture.villager();
        BlockPos start = villager.blockPosition();
        for (BlockPos wall : List.of(start.north(), start.south(), start.east(), start.west())) {
            helper.getLevel().setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
            helper.getLevel().setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 3);
            helper.getLevel().setBlock(wall.above(2), Blocks.STONE.defaultBlockState(), 3);
        }
        villager.getVillagerBrain().setMoveState(MoveState.FOLLOW, fixture.player());
        villager.setNoAi(false);
        long startedAt = helper.getLevel().getGameTime();
        long[] stoppedAt = {-1L};
        helper.onEachTick(() -> {
            long time = helper.getLevel().getGameTime();
            if (stoppedAt[0] < 0L) {
                if (villager.distanceToSqr(fixture.player()) >= 25.0D) {
                    if (time - startedAt > 40L) {
                        fixture.close();
                        helper.fail("Follow Me did not teleport a follower out of an unpathable enclosure");
                    }
                    return;
                }
                assertCaughtUp(helper, fixture);
                villager.getVillagerBrain().setMoveState(MoveState.STAY, fixture.player());
                villager.setPos(Vec3.atBottomCenterOf(start));
                stoppedAt[0] = time;
            }
            helper.assertTrue(villager.distanceToSqr(fixture.player()) > 144.0D,
                    "Stay Here villager teleported to its former leader");
            if (time - stoppedAt[0] >= 20L) {
                fixture.close();
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void unsafeOrCrampedDestinationsAreRejected(GameTestHelper helper) {
        List<String> previousBlacklist = Config.getInstance().unSafeBlocksToTeleportOn;
        try (Fixture fixture = createFixture(helper, Gender.MALE)) {
            Vec3 initialPosition = fixture.villager().position();
            FollowTask task = new FollowTask();
            long time = 0L;
            for (BlockState floor : List.of(Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                    Blocks.OAK_LEAVES.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
                setLandingFloor(helper, fixture.player().blockPosition(), floor);
                for (int attempt = 0; attempt < 5; attempt++, time += 10L) {
                    task.tick(helper.getLevel(), fixture.villager(), time);
                }
                helper.assertTrue(fixture.villager().position().equals(initialPosition),
                        "follower teleported onto unsafe terrain: " + floor);
            }

            setLandingFloor(helper, fixture.player().blockPosition(), Blocks.STONE.defaultBlockState());
            Config.getInstance().unSafeBlocksToTeleportOn = List.of("minecraft:stone");
            task.tick(helper.getLevel(), fixture.villager(), time);
            helper.assertTrue(fixture.villager().position().equals(initialPosition),
                    "follower ignored the configured teleport block blacklist");
            Config.getInstance().unSafeBlocksToTeleportOn = previousBlacklist;

            BlockPos playerPos = fixture.player().blockPosition();
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    helper.getLevel().setBlock(playerPos.offset(x, 1, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            task.tick(helper.getLevel(), fixture.villager(), time + 10L);
            helper.assertTrue(fixture.villager().position().equals(initialPosition),
                    "follower teleported into a space too short for its body");
            helper.succeed();
        } finally {
            Config.getInstance().unSafeBlocksToTeleportOn = previousBlacklist;
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void failedTeleportsRetryAfterTenTicksAndContinueFollowing(GameTestHelper helper) {
        try (Fixture fixture = createFixture(helper, Gender.MALE)) {
            FollowTask task = new FollowTask();
            Vec3 initialPosition = fixture.villager().position();
            BlockPos target = fixture.player().blockPosition();
            setLandingFloor(helper, target, Blocks.AIR.defaultBlockState());
            task.tick(helper.getLevel(), fixture.villager(), 0L);
            setLandingFloor(helper, target, Blocks.STONE.defaultBlockState());
            task.tick(helper.getLevel(), fixture.villager(), 9L);
            helper.assertTrue(fixture.villager().position().equals(initialPosition),
                    "failed teleport retried before its cooldown elapsed");
            fixture.villager().getRandom().setSeed(1L);
            task.tick(helper.getLevel(), fixture.villager(), 10L);
            assertCaughtUp(helper, fixture);

            fixture.villager().setPos(initialPosition);
            fixture.villager().getRandom().setSeed(1L);
            task.tick(helper.getLevel(), fixture.villager(), 20L);
            assertCaughtUp(helper, fixture);
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void ridingLeashedAndCarryingVillagersDoNotTeleport(GameTestHelper helper) {
        try (Fixture fixture = createFixture(helper, Gender.MALE);
             Fixture mount = createFixture(helper, Gender.FEMALE)) {
            VillagerEntityMCA villager = fixture.villager();
            Vec3 initialPosition = villager.position();
            FollowTask task = new FollowTask();
            helper.assertTrue(villager.startRiding(mount.villager(), true, true), "could not mount test villager");
            task.tick(helper.getLevel(), villager, 0L);
            helper.assertTrue(villager.position().equals(initialPosition) && villager.isPassenger(),
                    "riding follower teleported or dismounted");
            villager.stopRiding();
            villager.setPos(initialPosition);

            villager.setLeashedTo(fixture.player(), false);
            task.tick(helper.getLevel(), villager, 10L);
            helper.assertTrue(villager.position().equals(initialPosition) && villager.isLeashed(),
                    "leashed follower teleported");
            villager.removeLeash();

            helper.assertTrue(mount.villager().startRiding(villager, true, true), "could not attach test passenger");
            task.tick(helper.getLevel(), villager, 20L);
            helper.assertTrue(villager.position().equals(initialPosition) && villager.isVehicle(),
                    "follower carrying a passenger teleported");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void guardCombatRetainsMovementUntilCombatEnds(GameTestHelper helper) {
        try (Fixture fixture = createFixture(helper, Gender.MALE)) {
            VillagerEntityMCA villager = fixture.villager();
            villager.setVillagerData(villager.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(ProfessionsMCA.GUARD)));
            villager.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, fixture.player());
            Vec3 initialPosition = villager.position();
            FollowTask task = new FollowTask();
            task.tick(helper.getLevel(), villager, 0L);
            helper.assertTrue(villager.position().equals(initialPosition),
                    "Follow Me interrupted guard combat by teleporting");
            villager.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            villager.getRandom().setSeed(1L);
            task.tick(helper.getLevel(), villager, 1L);
            assertCaughtUp(helper, fixture);
            helper.succeed();
        }
    }

    private static Fixture createFixture(GameTestHelper helper, Gender gender) {
        BlockPos start = helper.absolutePos(new BlockPos(30, 4, 30));
        BlockPos target = start.east(20);
        prepareFlatArea(helper, start, 4, 4);
        prepareFlatArea(helper, target, 4, 4);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atBottomCenterOf(target));
        player.setOnGround(true);
        VillagerEntityMCA villager = VillagerFactory.newVillager(helper.getLevel())
                .withGender(gender).withAge(0).withPosition(Vec3.atBottomCenterOf(start))
                .spawn(EntitySpawnReason.STRUCTURE);
        villager.setNoAi(true);
        villager.setOnGround(true);
        villager.refreshBrain(helper.getLevel());
        villager.getBrain().setMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING, player);
        villager.getRandom().setSeed(1L);
        return new Fixture(villager, player);
    }

    private static void setLandingFloor(GameTestHelper helper, BlockPos target, BlockState floor) {
        // Clear the previous fluid first so water and lava cannot create safe stone landing spots.
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                helper.getLevel().setBlock(target.offset(x, -1, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                helper.getLevel().setBlock(target.offset(x, -1, z), floor, 3);
            }
        }
    }

    private static void assertCaughtUp(GameTestHelper helper, Fixture fixture) {
        helper.assertTrue(fixture.villager().distanceToSqr(fixture.player()) < 25.0D,
                "distant follower did not teleport near the player: " + fixture.villager().position());
        helper.assertTrue(helper.getLevel().noCollision(fixture.villager(), fixture.villager().getBoundingBox()),
                "follower's teleported bounding box intersects a block");
    }

    private record Fixture(VillagerEntityMCA villager, Player player) implements AutoCloseable {
        @Override
        public void close() {
            villager.discard();
            player.discard();
        }
    }
}
