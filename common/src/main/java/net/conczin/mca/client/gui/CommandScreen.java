package net.conczin.mca.client.gui;

import net.conczin.mca.network.Network;
import net.conczin.mca.network.c2s.ArmyOverviewRequest;
import net.conczin.mca.network.c2s.CommandSoldiersMessage;
import net.conczin.mca.server.world.data.Village;
import net.conczin.mca.util.compat.ButtonWidget;
import net.conczin.mca.client.gui.widget.WidgetUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Army selection uses the same projection and terrain renderer as the blueprint map. */
public final class CommandScreen extends ExtendedScreen {
    private record Marker(UUID id, String name, double x, double y, double z, int ditch) { }
    private final BlueprintScreen parent;
    private final Village village;
    private final BlueprintMapRenderer renderer = new BlueprintMapRenderer();
    private final BlueprintMapGeometry geometry;
    private List<Marker> soldiers = List.of(), targets = List.of();
    private final Set<UUID> selected = new LinkedHashSet<>();
    private final List<ButtonWidget> orderButtons = new ArrayList<>();
    private boolean canCommand;
    private boolean attackSelection;
    private boolean dragging, panning;
    private double startX, startY, pointerX, pointerY;
    private double centerX, centerZ;
    private float scale = 1;
    private int refreshTicks, ditchDistance = 24;
    private EditBox coordinateX, coordinateY, coordinateZ;
    private ButtonWidget modeButton, ditchButton;

    public CommandScreen(BlueprintScreen parent, Village village) {
        super(Component.translatable("gui.blueprint.command"));
        this.parent = parent;
        this.village = village;
        geometry = BlueprintMapGeometry.build(village, net.conczin.mca.server.world.data.RoomTypeResolver.create(village));
        centerX = village.getBox().getCenter().getX();
        centerZ = village.getBox().getCenter().getZ();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void init() {
        orderButtons.clear();
        addRenderableWidget(new ButtonWidget(8, 8, 45, 20, Component.translatable("gui.back"), button -> onClose()));
        int x = width - 166, y = 36;
        modeButton = button(x, y, 156, "gui.command.select", () -> {
            attackSelection = !attackSelection;
            modeButton.setMessage(Component.translatable(attackSelection ? "gui.command.attack_select" : "gui.command.select"));
        });
        modeButton.setTooltip(Tooltip.create(Component.translatable("gui.command.selection_hint")));
        button(x, y + 22, 76, "gui.command.follow", () -> send(CommandSoldiersMessage.Action.FOLLOW));
        button(x + 80, y + 22, 76, "gui.command.stay", () -> send(CommandSoldiersMessage.Action.STAY));
        button(x, y + 44, 76, "gui.command.free", () -> send(CommandSoldiersMessage.Action.FREE));
        button(x + 80, y + 44, 76, "gui.command.fit", this::fitArmy);
        button(x, y + 66, 76, "gui.command.guard", () -> send(CommandSoldiersMessage.Action.GUARD));
        button(x + 80, y + 66, 76, "gui.command.archer", () -> send(CommandSoldiersMessage.Action.ARCHER));
        button(x, y + 88, 156, "gui.command.inventory", () -> send(CommandSoldiersMessage.Action.INVENTORY))
                .setTooltip(Tooltip.create(Component.translatable("gui.command.inventory_hint")));
        button(x, y + 110, 24, "gui.command.minus", () -> setDitch(ditchDistance - 4));
        ditchButton = button(x + 26, y + 110, 104, "gui.command.ditch", () -> { });
        ditchButton.setMessage(Component.translatable("gui.command.ditch", ditchDistance));
        ditchButton.setTooltip(Tooltip.create(Component.translatable("gui.command.ditch_hint")));
        button(x + 132, y + 110, 24, "gui.command.plus", () -> setDitch(ditchDistance + 4));
        coordinateX = coordinate(x, y + 146, "X", (int) centerX);
        coordinateY = coordinate(x + 53, y + 146, "Y", minecraft.player == null ? 64 : minecraft.player.getBlockY());
        coordinateZ = coordinate(x + 106, y + 146, "Z", (int) centerZ);
        button(x, y + 170, 156, "gui.command.move", () -> {
            try { send(CommandSoldiersMessage.Action.MOVE_TO,
                    new BlockPos(Integer.parseInt(coordinateX.getValue()), Integer.parseInt(coordinateY.getValue()), Integer.parseInt(coordinateZ.getValue())), "", 0); }
            catch (NumberFormatException ignored) { }
        });
        Network.sendToServer(new ArmyOverviewRequest(village.getId()));
    }

    private EditBox coordinate(int x, int y, String label, int value) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, 49, 20, Component.literal(label)));
        box.setMaxLength(10);
        box.setValue(Integer.toString(value));
        box.setTooltip(Tooltip.create(Component.literal(label)));
        return box;
    }
    private ButtonWidget button(int x, int y, int width, String key, Runnable action) {
        ButtonWidget button = addRenderableWidget(new ButtonWidget(x, y, width, 20, Component.translatable(key), b -> action.run()));
        orderButtons.add(button);
        return button;
    }
    private void setDitch(int value) {
        ditchDistance = Math.clamp(value, 4, 128);
        ditchButton.setMessage(Component.translatable("gui.command.ditch", ditchDistance));
        send(CommandSoldiersMessage.Action.DITCH_DISTANCE, BlockPos.ZERO, "", ditchDistance);
    }
    private void send(CommandSoldiersMessage.Action action) { send(action, BlockPos.ZERO, "", 0); }
    private void send(CommandSoldiersMessage.Action action, BlockPos position, String target, int value) {
        if (canCommand && !selected.isEmpty()) Network.sendToServer(new CommandSoldiersMessage(village.getId(), action,
                selected.stream().limit(CommandSoldiersMessage.MAX_SELECTION).toList(), position, target, value));
    }
    @Override public void tick() {
        if (++refreshTicks % 20 == 0) Network.sendToServer(new ArmyOverviewRequest(village.getId()));
    }

    public void setOverview(int id, CompoundTag data) {
        if (id != village.getId()) return;
        canCommand = data.getBoolean("canCommand").orElse(false);
        soldiers = markers(data.getList("soldiers").orElseGet(ListTag::new));
        targets = markers(data.getList("targets").orElseGet(ListTag::new));
        Set<UUID> loaded = new HashSet<>();
        soldiers.forEach(marker -> loaded.add(marker.id));
        selected.retainAll(loaded);
        if (!canCommand) selected.clear();
    }
    private static List<Marker> markers(ListTag tags) {
        List<Marker> result = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            CompoundTag tag = tags.getCompound(i).orElseGet(CompoundTag::new);
            try { result.add(new Marker(UUID.fromString(tag.getString("id").orElse("")), tag.getString("name").orElse(""),
                    tag.getDouble("x").orElse(0D), tag.getDouble("y").orElse(0D), tag.getDouble("z").orElse(0D),
                    tag.getInt("ditchDistance").orElse(24))); }
            catch (IllegalArgumentException ignored) { }
        }
        return List.copyOf(result);
    }
    private BlueprintMapViewport viewport() {
        int half = Math.max(32, Math.min((width - 205) / 2, (height - 85) / 2));
        return BlueprintMapViewport.create((width - 180) / 2, height / 2 + 8, half, centerX, centerZ, scale);
    }
    private void fitArmy() {
        if (soldiers.isEmpty()) return;
        double minX = soldiers.stream().mapToDouble(Marker::x).min().orElse(centerX);
        double maxX = soldiers.stream().mapToDouble(Marker::x).max().orElse(centerX);
        double minZ = soldiers.stream().mapToDouble(Marker::z).min().orElse(centerZ);
        double maxZ = soldiers.stream().mapToDouble(Marker::z).max().orElse(centerZ);
        centerX = (minX + maxX) / 2;
        centerZ = (minZ + maxZ) / 2;
        scale = (float) Math.clamp((viewport().halfSize() * 2 - 16) / Math.max(16, Math.max(maxX - minX, maxZ - minZ)), 0.125, 8);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(context, mouseX, mouseY, partialTick);
        context.centeredText(font, Component.translatable("gui.command.title", village.getName()), width / 2, 10, 0xffffffff);
        context.text(font, Component.translatable("gui.command.selected", selected.size(), soldiers.size()), 10, 36, 0xffdddddd);
        BlueprintMapViewport view = viewport();
        renderer.render(context, view, geometry.get(null), null, true, true, true, minecraft.player,
                minecraft.player == null ? 0 : minecraft.player.getX(), minecraft.player == null ? 0 : minecraft.player.getZ(), mouseX, mouseY);
        if (attackSelection) drawMarkers(context, view, targets, 0xffff6655, mouseX, mouseY);
        drawMarkers(context, view, soldiers, 0xff55ddff, mouseX, mouseY);
        if (dragging) {
            int left = (int) Math.min(startX, pointerX), top = (int) Math.min(startY, pointerY);
            int right = (int) Math.max(startX, pointerX), bottom = (int) Math.max(startY, pointerY);
            context.fill(left, top, right + 1, bottom + 1, attackSelection ? 0x44ff5544 : 0x4455ccff);
            context.fill(left, top, right + 1, top + 1, 0xffffffff);
            context.fill(left, bottom, right + 1, bottom + 1, 0xffffffff);
            context.fill(left, top, left + 1, bottom + 1, 0xffffffff);
            context.fill(right, top, right + 1, bottom + 1, 0xffffffff);
        }
        if (!canCommand) context.text(font, Component.translatable("gui.command.denied"), 10, height - 23, 0xffff5555);
        else context.text(font, Component.translatable("gui.command.map_hint"), 10, height - 23, 0xffaaaaaa);
        for (ButtonWidget button : orderButtons) button.active = canCommand;
        drawCursorCoordinates(context, mouseX, mouseY);
    }
    private void drawMarkers(GuiGraphicsExtractor context, BlueprintMapViewport view, List<Marker> markers, int color, int mouseX, int mouseY) {
        for (Marker marker : markers) {
            int x = (int) view.screenX(marker.x), y = (int) view.screenY(marker.z);
            if (!view.containsInner(x - 2, y - 2) || !view.containsInner(x + 3, y + 3)) continue;
            if (selected.contains(marker.id)) context.fill(x - 3, y - 3, x + 4, y + 4, 0xffffff55);
            context.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (viewport().containsInner(event.x(), event.y())) {
            startX = pointerX = event.x(); startY = pointerY = event.y();
            dragging = event.button() == 0 && canCommand;
            panning = event.button() == 2;
            if (event.button() == 1 && canCommand && !selected.isEmpty()) {
                Marker enemy = attackSelection ? nearest(targets, event.x(), event.y()) : null;
                if (enemy != null) send(CommandSoldiersMessage.Action.ATTACK, BlockPos.ZERO, enemy.id.toString(), 0);
                else {
                    BlockPos position = mapPosition(event.x(), event.y());
                    coordinateX.setValue(Integer.toString(position.getX())); coordinateY.setValue(Integer.toString(position.getY())); coordinateZ.setValue(Integer.toString(position.getZ()));
                    send(CommandSoldiersMessage.Action.MOVE_TO, position, "", 0);
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        pointerX = Math.clamp(event.x(), viewport().left() + 1, viewport().right() - 1);
        pointerY = Math.clamp(event.y(), viewport().top() + 1, viewport().bottom() - 1);
        if (panning) { centerX -= dx / scale; centerZ -= dy / scale; return true; }
        return dragging || super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (panning) { panning = false; return true; }
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        boolean click = Math.hypot(event.x() - startX, event.y() - startY) < 4;
        List<Marker> pool = attackSelection ? targets : soldiers;
        List<Marker> chosen;
        if (click) {
            Marker nearest = nearest(pool, event.x(), event.y());
            chosen = nearest == null ? List.of() : List.of(nearest);
        } else {
            double left = Math.min(startX, pointerX), right = Math.max(startX, pointerX);
            double top = Math.min(startY, pointerY), bottom = Math.max(startY, pointerY);
            chosen = pool.stream().filter(marker -> viewport().screenX(marker.x) >= left && viewport().screenX(marker.x) <= right
                    && viewport().screenY(marker.z) >= top && viewport().screenY(marker.z) <= bottom).toList();
        }
        if (attackSelection) {
            if (!chosen.isEmpty()) send(CommandSoldiersMessage.Action.ATTACK, BlockPos.ZERO,
                    String.join(",", chosen.stream().limit(32).map(marker -> marker.id.toString()).toList()), 0);
        } else {
            selected.clear();
            chosen.stream().limit(CommandSoldiersMessage.MAX_SELECTION).forEach(marker -> selected.add(marker.id));
            if (!chosen.isEmpty()) { ditchDistance = chosen.getFirst().ditch; ditchButton.setMessage(Component.translatable("gui.command.ditch", ditchDistance)); }
        }
        return true;
    }
    private Marker nearest(List<Marker> pool, double x, double y) {
        return pool.stream().filter(marker -> Math.hypot(viewport().screenX(marker.x) - x, viewport().screenY(marker.z) - y) <= 6)
                .min(Comparator.comparingDouble(marker -> Math.hypot(viewport().screenX(marker.x) - x, viewport().screenY(marker.z) - y))).orElse(null);
    }

    private int orderHeight() {
        try { return Integer.parseInt(coordinateY.getValue()); }
        catch (NumberFormatException ignored) { return village.getBox().getCenter().getY(); }
    }

    private BlockPos mapPosition(double mouseX, double mouseY) {
        int x = (int) Math.floor(viewport().worldX(mouseX));
        int z = (int) Math.floor(viewport().worldZ(mouseY));
        int y = orderHeight();
        if (minecraft.level != null && minecraft.level.getChunkSource().hasChunk(x >> 4, z >> 4))
            y = minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private void drawCursorCoordinates(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!viewport().containsInner(mouseX, mouseY)) return;
        Marker marker = nearest(soldiers, mouseX, mouseY);
        if (marker == null && attackSelection) marker = nearest(targets, mouseX, mouseY);
        BlockPos position = marker == null ? mapPosition(mouseX, mouseY) : BlockPos.containing(marker.x, marker.y, marker.z);
        List<Component> tooltip = new ArrayList<>();
        if (marker != null) tooltip.add(Component.literal(marker.name));
        tooltip.add(Component.translatable("gui.command.coordinates", position.getX(), position.getY(), position.getZ()));
        if (marker == null && (minecraft.level == null || !minecraft.level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4)))
            tooltip.add(Component.translatable("gui.command.coordinates.order_height").withStyle(net.minecraft.ChatFormatting.GRAY));
        WidgetUtils.drawTooltip(context, font, tooltip, mouseX, mouseY);
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!viewport().containsInner(x, y)) return super.mouseScrolled(x, y, dx, dy);
        float next = (float) Math.clamp(scale * Math.pow(1.1, dy), 0.125, 8);
        BlueprintMapViewport zoomed = viewport().zoomedAround(x, y, next);
        centerX = zoomed.mapCenterX(); centerZ = zoomed.mapCenterZ(); scale = next;
        return true;
    }
}
