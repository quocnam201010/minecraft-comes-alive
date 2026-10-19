# Village governance and physical health

Administrators can set a player's rank in one village without completing its tasks:

```text
/mca-admin forceRank <player> <outlaw|peasant|merchant|noble|mayor|monarch> [villageId]
/mca-admin clearForcedRank <player> [villageId]
```

Omitting the ID uses the administrator's current village. The console should supply an ID from `/mca-admin listVillages`. Overrides survive restarts and affect only that village. Clearing an override restores ordinary requirements. Forcing a rank clears that player's individual ban; a subsequent ban clears their override. Forcing Monarch transfers the throne and makes the previous ruler a Mayor.

## Monarch controls

Right-click a blueprint and select **Army**. **My villages** selects a village where you are a Mayor or Monarch, or hold the Commander role, even while you are elsewhere in the same dimension. Opening the blueprint away from all villages automatically selects an accessible village when possible. **View nearby village** returns to local management. The selected village receives policy and settings changes; physical room scanning remains local. Players can be selected from known/online players or entered by name or UUID. An individual ban makes the player an Outlaw in this village. **Lift ban** removes it. The map no longer displays the overlapping rank/reputation/building/population block; these remain on the Rank page.

The **Villages** section creates or replaces one targeting rule per foreign village. Choose whether it targets NPC residents, players, or both, and a minimum player rank. Player ranks are evaluated in the foreign village, before political Outlaw status, to avoid circular results during mutual hostilities. Higher ranks qualify. The target must be known to that village through a visit, reputation, or an administrator's rank assignment. Qualifying players remain targets wherever they travel; NPC membership follows their home village. Removing a targeting rule ends that rule's hostility. Other individual bans and village rules still apply.

Guards and archers acquire these enemies through their normal sensing and combat AI. Mercenaries and professions in `#mca:military` are also military professions; a soldier needs a home village to act under its policies. Rules do not force-load terrain or send an army across unloaded chunks. Creative and Spectator players retain their normal immunity. Archer arrows can hit declared enemy villagers while continuing to ignore friendly villagers.

Only the current Monarch may change army policy, bans, food policy, and appointments. Physical presence in the village is no longer required. Existing tax and population settings and renaming require at least Mayor rank. The server checks permissions and all submitted values. A Monarch cannot ban themselves or target their own village. Lifting a ban or rule stops attacks caused solely by that policy; it does not remove another applicable ban.

## Ownership and appointments

Ranks remain per village. Each village has one persistent Monarch. The first eligible player to claim an unowned throne becomes its Monarch. The older universal requirements override also respects existing ownership. Older saves with multiple forced Monarchs retain one deterministically and make the others Mayors; merging villages preserves the surviving village's Monarch.

The Monarch's **Appointments** page can immediately appoint a player as Peasant, Merchant, Noble, or Mayor without reputation requirements. These appointments remain until manually changed. Meeting the Monarch requirements while the throne is occupied does not replace its owner; the qualifying player receives the highest lower civil rank they qualify for.

Commander is a separate village-specific military role. It does not change civil rank or grant Mayor's civil settings permissions. Only the Monarch can grant or revoke it, and the recipient must already be Peasant or higher. For an offline recipient, first appoint an eligible civil rank so the server can verify eligibility. A ban or an Outlaw appointment revokes the role; losing eligibility blocks army access. Existing saves with the old Commander rank migrate that assignment into a role and restore normal civil progression. Old hostility filters set to Commander migrate to Mayor, since Commander no longer belongs to the civil rank scale.

**Transfer crown to Mayor** asks for confirmation, makes that village's selected Mayor its Monarch, and makes the former Monarch a Mayor. For an offline successor, appoint them Mayor first. Names must be online or known to the village; UUIDs can be used for offline players.

## Soldier command and equipment

"Soldier" refers collectively to guards, archers, mercenaries, and professions tagged `#mca:military` with a home village. Monarchs and Commanders can open **Command** for their village. Only loaded soldiers appear, as blue dots; selected soldiers have a yellow border. Blue soldier dots also appear on the ordinary blueprint map. The command overview refreshes once per second.

Left-click while holding a blueprint to open Command directly. This shortcut prefers a village where you have army authority, even if you are currently in a different village, and does not strike entities or break blocks. Right-click continues to open the ordinary blueprint. Hovering over the command map shows XYZ coordinates; hovering a dot also identifies its soldier or target. Loaded terrain supplies the ground height. Where terrain is unavailable, the displayed Y uses the entered order height and is labeled accordingly.

- Left-click or drag a rectangle to select up to 128 soldiers.
- Choose **Follow me**, **Stay**, **Move freely**, **Make guard**, or **Make archer**.
- Enter X, Y, and Z and choose **Move and hold position**. Right-clicking the map also sends a move order to the displayed map coordinates. Soldiers pathfind and then hold the destination, returning to it after displacement or combat.
- Switch **Select soldiers** to **Choose attack targets**. Red dots show loaded possible targets near the army. Click or drag over up to 32 targets to give selected soldiers a sequence of attack orders. Players, villagers, and ordinary mobs can be targeted. Creative/Spectator players, the issuing commander, and the village's own soldiers are protected.
- Middle-drag pans, scrolling zooms around the pointer, and **Fit soldiers** centers the loaded army.

Orders and the ditch setting survive saving, unloading, and zombification/curing. Movement does not force-load chunks. Orders into unloaded terrain wait for an accessible route. Revoking an issuing officer's military authority cancels their outstanding orders; changing an eligible civil rank leaves the independent Commander role intact. Soldiers pause following when their leader is unavailable and resume when the leader returns to the same dimension.

Following soldiers assist against a mob their leader attacks. **Ditch** sets how far they can travel from that leader before dropping combat and returning (4-128 blocks, default 24). They cannot immediately reacquire the discarded target while returning.

**Supplies & equipment** opens one selected soldier's inventory when within eight blocks. The ordinary villager interaction **Inventory** button also grants this access to their Monarch and Commanders. The first three rows hold supplies. The first six slots of the fourth row hold player-supplied main-hand equipment, off-hand equipment, helmet, chestplate, leggings, and boots. The last three slots are disabled. Equipment is limited to one item per slot, and armor slots validate armor type. Shift-click moves supplies; drag items into equipment slots explicitly.

Generated default equipment never appears in these slots and cannot be withdrawn. Player-supplied gear retains durability and components, overrides default equipment, and can be removed normally. Access is revoked when the player moves out of range or loses authority.

Right-click a soldier and use **Defaults: ON/OFF** on the main interaction screen to toggle generated equipment. Only that soldier's village Monarch or Commanders can change it, within eight blocks. Turning defaults off immediately removes generated weapons, shields, and armor; the soldier uses only supplied equipment and suitable weapons/armor from their supplies, leaving other slots empty. Explicit equipment slots take priority. Empty-handed soldiers fight barehanded, including archers without a supplied bow. Combat, duty changes, and profession changes do not restore disabled defaults. The setting is per soldier, survives saving and zombification/curing, and starts ON for existing soldiers. Turn it back ON to allow normal equipment generation again.

## Village labels

Villager interactions show their home village, or "unaffiliated" if they have none. Press **V** to toggle the home village below villager nameplates; the key can be rebound under Minecraft Comes Alive in Controls. Names update after village renaming and follow the villager's home membership rather than the village they happen to be standing in. The existing nameplate visibility and distance settings still apply.

The physical-health and village rows sit at the bottom left, below the existing hearts, traits, family, and conversation-analysis controls. Long village names are shortened in the row and shown in full on hover. Expanded information tooltips draw above these rows.

## Physical health and food warehouses

Physical health is shown in the villager interaction screen and saved per villager. Its range is 0–1000. Bonuses scale linearly from zero to +40 maximum HP, +2 attack damage, and a 1.1 multiplier on movement speed at 1000. Attribute modifiers have stable identifiers, so saving, loading and repeated updates do not stack bonuses. Existing traits, equipment and other attribute modifiers continue to apply. Increased maximum HP is filled through the villager's existing healing behavior; reducing the maximum clamps current HP safely.

Hover over **Physical Health** in the interaction screen to see the villager's current maximum-health, attack-damage, and movement-speed bonuses alongside the maximum bonuses at 1000. The speed bonus shows both its multiplier and percentage increase.

Every 8,400 loaded entity ticks (seven minutes at 20 ticks/second), that villager:

1. Loses 30 physical health, stopping at zero.
2. Consumes food from its home village's loaded food warehouses if eligible.
3. Gains the food's points, clamping physical health to 1000. The entire food item must still fit the meal allowance.

The saved partial timer resumes after loading. No decay, meals or catch-up ticks accrue while the villager is unloaded. Ordinary server tick-rate changes affect elapsed real time in the same way as other Minecraft timers.

Register a normal enclosed MCA room containing at least **eight chest blocks** as a **Food Warehouse**. A double chest contains two chest blocks. Modded chests in `#mca:chests` work when they expose Minecraft's `Container` interface. All eight chest blocks must currently exist and be loaded for the warehouse to supply a meal. Destroyed chests or stale scan data do not provide food.

Food uses its stack's `minecraft:food` component, including modded foods and custom food components. Points per whole item are `floor(nutrition + saturation)`; cooked beef gives 20. Harmful foods rejected by MCA's existing food-safety checks are skipped. No food item is partly consumed. Bowls and other consumption remainders are preserved.

Warehouses are processed by building ID, then chests by Y, X and Z, then slots from first to last. Each affordable stack is consumed before advancing; food that would exceed the remaining meal budget is skipped. Duplicate chest references are counted once. A final whole item may exceed the remaining health capacity; the excess is discarded so bread and other foods can reach exactly 1000. Only the necessary final item is consumed.

The blueprint's **Food policy** page allows the Monarch to choose everyone, military professions only, or an explicit list of professions. The meal budget is 0–200 points per villager per cycle and defaults to 200. Zero disables warehouse feeding while decay continues. These policies survive restarts.

## Verification

`VillageGovernanceGameTests` exercises administrator commands, remote permissions, ownership transfer, persistent appointments and orders, equipment ownership and conversion, real soldier movement and combat, follow assistance and ditch distance, worldwide foreign-rank targeting, mutual diplomacy, attribute scaling, loaded-time cycles, deterministic food consumption, the 1000-point cap, custom foods, profession policies, chest requirements, consumption remainders, unloaded-chunk protection, village labels and enemy-villager arrow collisions. Run with:

```text
gradlew :neoforge:runGameTestServer -PmcaGameTest=VillageGovernanceGameTests
```

Use the repository's `.gradle-user-home` on G: as `GRADLE_USER_HOME` for this workspace.
