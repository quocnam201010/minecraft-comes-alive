# Village governance and physical health

Administrators can set a player's rank in one village without completing its tasks:

```text
/mca-admin forceRank <player> <outlaw|peasant|merchant|noble|mayor|monarch> [villageId]
/mca-admin clearForcedRank <player> [villageId]
```

Omitting the ID uses the administrator's current village. The console should supply an ID from `/mca-admin listVillages`. Overrides survive restarts and affect only that village. Clearing an override restores ordinary requirements. Forcing a rank clears that player's individual ban; a subsequent ban clears their override.

## Monarch controls

Open a blueprint in your village and select **Army**. Players can be selected from known/online players or entered by name or UUID. An individual ban makes the player an Outlaw in this village. **Lift ban** removes it.

The **Villages** section creates or replaces one targeting rule per foreign village. Choose whether it targets NPC residents, players, or both, and a minimum player rank. Player ranks are evaluated in the foreign village, before political Outlaw status, to avoid circular results during mutual hostilities. Higher ranks qualify. The target must be known to that village through a visit, reputation, or an administrator's rank assignment. Qualifying players remain targets wherever they travel; NPC membership follows their home village. Removing a targeting rule ends that rule's hostility. Other individual bans and village rules still apply.

Guards and archers acquire these enemies through their normal sensing and combat AI. Mercenaries and professions in `#mca:military` are also military professions; a soldier needs a home village to act under its policies. Rules do not force-load terrain or send an army across unloaded chunks. Creative and Spectator players retain their normal immunity. Archer arrows can hit declared enemy villagers while continuing to ignore friendly villagers.

Only a current Monarch standing in the village may change its policies. The server checks permissions and all submitted values. A Monarch cannot ban themselves or target their own village. Lifting a ban or rule stops attacks caused solely by that policy; it does not remove another applicable ban.

## Physical health and food warehouses

Physical health is shown in the villager interaction screen and saved per villager. Its range is 0–1000. Bonuses scale linearly from zero to +40 maximum HP, +2 attack damage, and a 1.1 multiplier on movement speed at 1000. Attribute modifiers have stable identifiers, so saving, loading and repeated updates do not stack bonuses. Existing traits, equipment and other attribute modifiers continue to apply. Increased maximum HP is filled through the villager's existing healing behavior; reducing the maximum clamps current HP safely.

Every 8,400 loaded entity ticks (seven minutes at 20 ticks/second), that villager:

1. Loses 30 physical health, stopping at zero.
2. Consumes food from its home village's loaded food warehouses if eligible.
3. Gains the food's points without exceeding 1000 physical health or its meal budget.

The saved partial timer resumes after loading. No decay, meals or catch-up ticks accrue while the villager is unloaded. Ordinary server tick-rate changes affect elapsed real time in the same way as other Minecraft timers.

Register a normal enclosed MCA room containing at least **eight chest blocks** as a **Food Warehouse**. A double chest contains two chest blocks. Modded chests in `#mca:chests` work when they expose Minecraft's `Container` interface. All eight chest blocks must currently exist and be loaded for the warehouse to supply a meal. Destroyed chests or stale scan data do not provide food.

Food uses its stack's `minecraft:food` component, including modded foods and custom food components. Points per whole item are `floor(nutrition + saturation)`; cooked beef gives 20. Harmful foods rejected by MCA's existing food-safety checks are skipped. No food item is partly consumed. Bowls and other consumption remainders are preserved.

Warehouses are processed by building ID, then chests by Y, X and Z, then slots from first to last. Each affordable stack is consumed before advancing; food that would exceed the remaining budget is skipped. Duplicate chest references are counted once. Food is not wasted beyond the remaining physical-health capacity.

The blueprint's **Food policy** page allows the Monarch to choose everyone, military professions only, or an explicit list of professions. The meal budget is 0–200 points per villager per cycle and defaults to 200. Zero disables warehouse feeding while decay continues. These policies survive restarts.

## Verification

`VillageGovernanceGameTests` exercises administrator commands, permission rejection, save/load behavior, worldwide foreign-rank targeting, mutual diplomacy, attribute scaling, loaded-time cycles, deterministic food consumption, custom food components, profession policies, real chest requirements, consumption remainders, unloaded-chunk protection and enemy-villager arrow collisions. Run with:

```text
gradlew :neoforge:runGameTestServer -PmcaGameTest=VillageGovernanceGameTests
```

Use the repository's `.gradle-user-home` on G: as `GRADLE_USER_HOME` for this workspace.
