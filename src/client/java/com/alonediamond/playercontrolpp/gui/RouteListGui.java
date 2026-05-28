package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.route.Route;
import com.alonediamond.playercontrolpp.route.RouteManager;
import com.alonediamond.playercontrolpp.route.RouteNode;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class RouteListGui extends Screen {

    private static final int TOP = 40;
    private static final int LEFT_X = 10;
    private static final int LEFT_W = 180;
    private static final int RIGHT_X = 200;
    private static final int LEFT_ITEM_H = 20;
    private static final int WPT_ROW_H = 24;
    // Waypoint field layout: only X and Z (Y is ignored)
    private static final int FIELD_X = RIGHT_X + 50;
    private static final int FIELD_W = 62;
    private static final int FIELD_GAP = 12;

    private final Screen parent;
    private Route selectedRoute;
    private int leftScroll;
    private int rightScroll;

    private TextFieldWidget nameField;
    private final List<WaypointFields> waypointFields = new ArrayList<>();
    private TextFieldWidget radiusField;
    private TextFieldWidget loopField;
    private TextFieldWidget layerIncField;
    private boolean dirty;
    private final List<WptHitArea> wptHitAreas = new ArrayList<>();

    public RouteListGui(Screen parent) {
        super(Text.of("Route Flow System"));
        this.parent = parent;
    }

    @Override
    public void close() {
        if (dirty) RouteManager.getInstance().saveRoutes();
        if (parent != null) {
            MinecraftClient.getInstance().setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.leftScroll = 0;
        this.rightScroll = 0;
        this.waypointFields.clear();
        this.wptHitAreas.clear();

        // Left panel: add/remove
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.add")),
                btn -> {
                    Route route = RouteManager.getInstance().addRoute(
                            StringUtils.translate("playercontrolpp.gui.route.new_route"));
                    selectedRoute = route;
                    dirty = true;
                    rebuildWaypointFields();
                    refreshFieldValues();
                })
                .dimensions(LEFT_X, TOP, 85, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.remove")),
                btn -> {
                    if (selectedRoute != null) {
                        RouteManager.getInstance().removeRoute(selectedRoute);
                        selectedRoute = null;
                        dirty = true;
                        rebuildWaypointFields();
                    }
                })
                .dimensions(LEFT_X + 90, TOP, 85, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.back")),
                btn -> close())
                .dimensions(this.width - 55, 10, 45, 20)
                .build());

        // Name field
        nameField = new TextFieldWidget(textRenderer, FIELD_X, TOP, 140, 18, Text.empty());
        nameField.setChangedListener(s -> {
            if (selectedRoute != null) { selectedRoute.setName(s); dirty = true; }
        });
        this.addSelectableChild(nameField);

        // Settings fields
        radiusField = new TextFieldWidget(textRenderer, FIELD_X, 0, 55, 18, Text.empty());
        radiusField.setChangedListener(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setArrivalRadius(Double.parseDouble(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addSelectableChild(radiusField);

        loopField = new TextFieldWidget(textRenderer, 0, 0, 45, 18, Text.empty());
        loopField.setChangedListener(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setLoopCount(Integer.parseInt(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addSelectableChild(loopField);

        layerIncField = new TextFieldWidget(textRenderer, 0, 0, 40, 18, Text.empty());
        layerIncField.setChangedListener(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setLayerIncrement(Integer.parseInt(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addSelectableChild(layerIncField);

        rebuildWaypointFields();
        refreshFieldValues();
    }

    // --- Waypoint management ---

    private void rebuildWaypointFields() {
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) this.remove(tf);
        }
        waypointFields.clear();
        if (selectedRoute == null) return;
        for (int i = 0; i < selectedRoute.getNodes().size(); i++) addWaypointRow(i);
    }

    private void addWaypointRow(int index) {
        WaypointFields wf = new WaypointFields(index);
        waypointFields.add(index, wf);
        for (TextFieldWidget tf : wf.fields) this.addSelectableChild(tf);
    }

    private void rebuildAllWaypointRows() {
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) this.remove(tf);
        }
        waypointFields.clear();
        if (selectedRoute != null) {
            for (int i = 0; i < selectedRoute.getNodes().size(); i++) addWaypointRow(i);
        }
    }

    private void refreshFieldValues() {
        boolean hasSel = selectedRoute != null;
        boolean showLayer = hasSel && selectedRoute.isLayerControlEnabled();
        nameField.setEditable(hasSel);
        radiusField.setEditable(hasSel);
        loopField.setEditable(hasSel);
        layerIncField.setEditable(showLayer);

        if (hasSel) {
            nameField.setText(selectedRoute.getName());
            radiusField.setText(String.format("%.1f", selectedRoute.getArrivalRadius()));
            loopField.setText(String.valueOf(selectedRoute.getLoopCount()));
            layerIncField.setText(String.valueOf(selectedRoute.getLayerIncrement()));
        } else {
            nameField.setText("");
            radiusField.setText("");
            loopField.setText("");
            layerIncField.setText("");
        }

        for (WaypointFields wf : waypointFields) {
            RouteNode node = selectedRoute.getNodes().get(wf.nodeIndex);
            wf.fields.get(0).setText(String.format("%.1f", node.x));
            wf.fields.get(1).setText(String.format("%.1f", node.z));
        }
    }

    private int getRightContentHeight() {
        if (selectedRoute == null) return 0;
        int n = selectedRoute.getNodes().size();
        return 26 + 18 + n * WPT_ROW_H + 22 + 46 + 24 + 14;
    }

    // --- Render ---

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.title")),
                this.width / 2, 12, 0xFFFFFFFF);

        // --- Left panel ---
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int listTop = TOP + 30;
        int maxLeftVisible = (this.height - listTop - 10) / LEFT_ITEM_H;
        if (leftScroll < 0) leftScroll = 0;

        context.fill(LEFT_X, listTop, LEFT_X + LEFT_W, listTop + maxLeftVisible * LEFT_ITEM_H, 0x20FFFFFF);

        for (int i = leftScroll; i < Math.min(routes.size(), leftScroll + maxLeftVisible); i++) {
            int y = listTop + (i - leftScroll) * LEFT_ITEM_H;
            Route route = routes.get(i);
            boolean isSelected = route == selectedRoute;
            int bg = isSelected ? 0x40FFFFFF : 0x0;
            int color = isSelected ? 0xFF55FF55 : 0xFFCCCCCC;
            context.fill(LEFT_X + 1, y, LEFT_X + LEFT_W - 1, y + LEFT_ITEM_H - 1, bg);
            context.drawTextWithShadow(textRenderer, Text.of(route.getName()), LEFT_X + 4, y + 5, color);
        }

        // --- Right panel ---
        if (selectedRoute == null) {
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.no_selection")),
                    RIGHT_X + 10, TOP + 10, 0xFF888888);
            return;
        }

        int rightH = this.height - TOP - 10;
        int contentH = getRightContentHeight();
        int maxRightScroll = Math.max(0, contentH - rightH);
        if (rightScroll > maxRightScroll) rightScroll = maxRightScroll;
        if (rightScroll < 0) rightScroll = 0;

        int ry = TOP - rightScroll;

        // Name
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        nameField.setX(FIELD_X);
        nameField.setY(ry + 2);
        nameField.render(context, mouseX, mouseY, delta);
        ry += 26;

        // Waypoints header
        context.drawTextWithShadow(textRenderer,
                Text.of("-- " + StringUtils.translate("playercontrolpp.gui.route.waypoints") + " --"),
                RIGHT_X + 10, ry + 2, 0xFFAAAAAA);
        ry += 18;

        // Waypoint rows (X + Z only, no Y)
        wptHitAreas.clear();
        List<RouteNode> nodes = selectedRoute.getNodes();
        int xFieldX = FIELD_X;
        int zFieldX = FIELD_X + FIELD_W + FIELD_GAP;

        for (int i = 0; i < nodes.size(); i++) {
            String label;
            if (i == 0) label = StringUtils.translate("playercontrolpp.gui.route.node_start");
            else if (i == nodes.size() - 1) label = StringUtils.translate("playercontrolpp.gui.route.node_end");
            else label = StringUtils.translate("playercontrolpp.gui.route.node_mid") + " " + i;

            context.drawTextWithShadow(textRenderer, Text.of(label),
                    RIGHT_X, ry + 4, 0xFFFFFFFF);

            // X field
            context.drawTextWithShadow(textRenderer, Text.of("X:"), xFieldX - 12, ry + 4, 0xFFCCCCCC);
            if (i < waypointFields.size()) {
                TextFieldWidget tf = waypointFields.get(i).fields.get(0);
                tf.setX(xFieldX);
                tf.setY(ry + 2);
                tf.render(context, mouseX, mouseY, delta);
            }

            // Z field
            context.drawTextWithShadow(textRenderer, Text.of("Z:"), zFieldX - 12, ry + 4, 0xFFCCCCCC);
            if (i < waypointFields.size()) {
                TextFieldWidget tf = waypointFields.get(i).fields.get(1);
                tf.setX(zFieldX);
                tf.setY(ry + 2);
                tf.render(context, mouseX, mouseY, delta);
            }

            // [Set] button
            int setBtnX = zFieldX + FIELD_W + 8;
            int setBtnW = textRenderer.getWidth("[Set]");
            int setColor = 0xFF55FFFF;
            if (mouseX >= setBtnX && mouseX <= setBtnX + setBtnW
                    && mouseY >= ry && mouseY <= ry + WPT_ROW_H) {
                setColor = 0xFFFFFF55;
            }
            context.drawTextWithShadow(textRenderer, Text.of("[Set]"), setBtnX, ry + 4, setColor);

            // [X] button (intermediate only)
            int xBtnX = setBtnX + setBtnW + 8;
            int xBtnW = 0;
            if (i > 0 && i < nodes.size() - 1) {
                xBtnW = textRenderer.getWidth("[X]");
                int xColor = 0xFFFF5555;
                if (mouseX >= xBtnX && mouseX <= xBtnX + xBtnW
                        && mouseY >= ry && mouseY <= ry + WPT_ROW_H) {
                    xColor = 0xFFFFFF55;
                }
                context.drawTextWithShadow(textRenderer, Text.of("[X]"), xBtnX, ry + 4, xColor);
            }

            wptHitAreas.add(new WptHitArea(ry, setBtnX, setBtnW, xBtnX, xBtnW, i));
            ry += WPT_ROW_H;
        }

        // [+ Add Node] button
        String addLabel = "[+ " + StringUtils.translate("playercontrolpp.gui.route.add_node") + "]";
        int addBtnW = textRenderer.getWidth(addLabel);
        int addBtnX = zFieldX + FIELD_W + 10;
        int addBtnY = ry + 2;
        int addColor = 0xFF55FF55;
        if (mouseX >= addBtnX && mouseX <= addBtnX + addBtnW
                && mouseY >= addBtnY - 2 && mouseY <= addBtnY + 14) {
            addColor = 0xFFFFFF55;
        }
        context.drawTextWithShadow(textRenderer, Text.of(addLabel), addBtnX, addBtnY, addColor);
        wptHitAreas.add(new WptHitArea(addBtnX, addBtnY, addBtnW, -1, 0, 0, -1));
        ry += 22;

        // Settings row 1: arrival radius + loop count
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.arrival_radius") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        radiusField.setX(FIELD_X);
        radiusField.setY(ry + 2);
        radiusField.render(context, mouseX, mouseY, delta);

        int lcLabelX = FIELD_X + 70;
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.loop_count") + ":"),
                lcLabelX, ry + 4, 0xFFFFFFFF);
        loopField.setX(lcLabelX + 48);
        loopField.setY(ry + 2);
        loopField.render(context, mouseX, mouseY, delta);
        ry += 24;

        // Settings row 2: layer increment (only shown when LayerCtrl is ON)
        if (selectedRoute.isLayerControlEnabled()) {
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.layer_increment") + ":"),
                    RIGHT_X, ry + 4, 0xFFFFFFFF);
            layerIncField.setX(FIELD_X);
            layerIncField.setY(ry + 2);
            layerIncField.render(context, mouseX, mouseY, delta);
            ry += 24;
        }

        // Settings row 3: Sprint + LayerCtrl toggles
        int toggleY = ry + 4;

        String sprintLabel = selectedRoute.isSprintEnabled()
                ? "[Sprint: ON]" : "[Sprint: OFF]";
        int sprintW = textRenderer.getWidth(sprintLabel);
        int sprintColor = selectedRoute.isSprintEnabled() ? 0xFF55FF55 : 0xFF888888;
        context.drawTextWithShadow(textRenderer, Text.of(sprintLabel), RIGHT_X, toggleY, sprintColor);

        String lcLabel = selectedRoute.isLayerControlEnabled()
                ? "[LayerCtrl: ON]" : "[LayerCtrl: OFF]";
        int lcW = textRenderer.getWidth(lcLabel);
        int lcX = RIGHT_X + sprintW + 20;
        int lcColor = selectedRoute.isLayerControlEnabled() ? 0xFF55FF55 : 0xFF888888;
        context.drawTextWithShadow(textRenderer, Text.of(lcLabel), lcX, toggleY, lcColor);

        // Record toggle hit areas
        wptHitAreas.add(new WptHitArea(ry, RIGHT_X, sprintW, lcX, lcW, -2));

        ry += 24;

        if (!selectedRoute.getDimensionId().isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.of("Dim: " + selectedRoute.getDimensionId()),
                    RIGHT_X, ry + 2, 0xFF888888);
        }
    }

    // --- Mouse ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int listTop = TOP + 30;
        int maxLeftVisible = (this.height - listTop - 10) / LEFT_ITEM_H;

        for (int i = leftScroll; i < Math.min(routes.size(), leftScroll + maxLeftVisible); i++) {
            int y = listTop + (i - leftScroll) * LEFT_ITEM_H;
            if (mouseX >= LEFT_X && mouseX <= LEFT_X + LEFT_W
                    && mouseY >= y && mouseY <= y + LEFT_ITEM_H) {
                selectedRoute = routes.get(i);
                rebuildWaypointFields();
                refreshFieldValues();
                return true;
            }
        }

        if (selectedRoute != null) {
            for (WptHitArea area : wptHitAreas) {
                // Sprint / LayerCtrl toggles (nodeIndex == -2)
                if (area.nodeIndex == -2) {
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        selectedRoute.setSprintEnabled(!selectedRoute.isSprintEnabled());
                        dirty = true;
                        return true;
                    }
                    if (area.xBtnW > 0 && mouseX >= area.xBtnX && mouseX <= area.xBtnX + area.xBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        selectedRoute.setLayerControlEnabled(!selectedRoute.isLayerControlEnabled());
                        dirty = true;
                        refreshFieldValues();
                        return true;
                    }
                    continue;
                }
                if (area.isAddButton) {
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y - 2 && mouseY <= area.y + 14) {
                        addWaypointAtEnd();
                        return true;
                    }
                } else {
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        var player = MinecraftClient.getInstance().player;
                        if (player != null) {
                            RouteNode node = selectedRoute.getNodes().get(area.nodeIndex);
                            node.x = player.getX();
                            node.y = player.getY();
                            node.z = player.getZ();
                            selectedRoute.setDimension(player.getWorld().getRegistryKey());
                            dirty = true;
                            refreshFieldValues();
                        }
                        return true;
                    }
                    if (area.xBtnW > 0
                            && mouseX >= area.xBtnX && mouseX <= area.xBtnX + area.xBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        removeWaypoint(area.nodeIndex);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void addWaypointAtEnd() {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        int insertIdx = nodes.size() - 1;
        RouteNode newNode = new RouteNode();
        if (insertIdx > 0) {
            RouteNode cur = nodes.get(insertIdx - 1);
            RouteNode next = nodes.get(insertIdx);
            newNode.x = (cur.x + next.x) / 2.0;
            newNode.y = (cur.y + next.y) / 2.0;
            newNode.z = (cur.z + next.z) / 2.0;
        }
        nodes.add(insertIdx, newNode);
        dirty = true;
        rebuildAllWaypointRows();
        refreshFieldValues();
    }

    private void removeWaypoint(int index) {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        if (nodes.size() <= 2) return;
        if (index <= 0 || index >= nodes.size() - 1) return;
        nodes.remove(index);
        dirty = true;
        rebuildAllWaypointRows();
        refreshFieldValues();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (mouseX >= RIGHT_X) {
            int maxScroll = Math.max(0, getRightContentHeight() - (this.height - TOP - 10));
            rightScroll = Math.max(0, Math.min(rightScroll - (int) vAmount * 20, maxScroll));
        } else {
            List<Route> routes = RouteManager.getInstance().getRoutes();
            int listTop = TOP + 30;
            int maxVisible = (this.height - listTop - 10) / LEFT_ITEM_H;
            int maxScroll = Math.max(0, routes.size() - maxVisible);
            leftScroll = Math.max(0, Math.min(leftScroll - (int) vAmount, maxScroll));
        }
        return true;
    }

    // --- Keyboard ---

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (nameField.isFocused()) return nameField.charTyped(chr, modifiers);
        if (radiusField.isFocused()) return radiusField.charTyped(chr, modifiers);
        if (loopField.isFocused()) return loopField.charTyped(chr, modifiers);
        if (layerIncField.isFocused()) return layerIncField.charTyped(chr, modifiers);
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                if (tf.isFocused()) return tf.charTyped(chr, modifiers);
            }
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && !nameField.isFocused() && !radiusField.isFocused()
                && !loopField.isFocused() && !layerIncField.isFocused()) {
            boolean anyFocused = false;
            for (WaypointFields wf : waypointFields) {
                for (TextFieldWidget tf : wf.fields) {
                    if (tf.isFocused()) { anyFocused = true; break; }
                }
                if (anyFocused) break;
            }
            if (!anyFocused) { close(); return true; }
        }

        if (nameField.isFocused()) return nameField.keyPressed(keyCode, scanCode, modifiers);
        if (radiusField.isFocused()) return radiusField.keyPressed(keyCode, scanCode, modifiers);
        if (loopField.isFocused()) return loopField.keyPressed(keyCode, scanCode, modifiers);
        if (layerIncField.isFocused()) return layerIncField.keyPressed(keyCode, scanCode, modifiers);
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                if (tf.isFocused()) return tf.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- Helpers ---

    private static class WptHitArea {
        final int y, setBtnX, setBtnW, xBtnX, xBtnW, nodeIndex;
        final boolean isAddButton;
        WptHitArea(int y, int setBtnX, int setBtnW, int xBtnX, int xBtnW, int nodeIndex) {
            this.y = y; this.setBtnX = setBtnX; this.setBtnW = setBtnW;
            this.xBtnX = xBtnX; this.xBtnW = xBtnW; this.nodeIndex = nodeIndex;
            this.isAddButton = false;
        }
        WptHitArea(int x, int y, int w, int nodeIndex, int a, int b, int c) {
            this.y = y; this.setBtnX = x; this.setBtnW = w;
            this.xBtnX = 0; this.xBtnW = 0; this.nodeIndex = -1;
            this.isAddButton = true;
        }
    }

    private class WaypointFields {
        int nodeIndex;
        List<TextFieldWidget> fields = new ArrayList<>(2);

        WaypointFields(int nodeIndex) {
            this.nodeIndex = nodeIndex;
            int xFieldX = FIELD_X;
            int zFieldX = FIELD_X + FIELD_W + FIELD_GAP;

            // X field
            TextFieldWidget xf = new TextFieldWidget(textRenderer, xFieldX, 0, FIELD_W, 18, Text.empty());
            final int idx = nodeIndex;
            xf.setChangedListener(s -> {
                if (selectedRoute != null && idx < selectedRoute.getNodes().size()) {
                    try { selectedRoute.getNodes().get(idx).x = Double.parseDouble(s); dirty = true; }
                    catch (NumberFormatException ignored) {}
                }
            });
            fields.add(xf);

            // Z field
            TextFieldWidget zf = new TextFieldWidget(textRenderer, zFieldX, 0, FIELD_W, 18, Text.empty());
            zf.setChangedListener(s -> {
                if (selectedRoute != null && idx < selectedRoute.getNodes().size()) {
                    try { selectedRoute.getNodes().get(idx).z = Double.parseDouble(s); dirty = true; }
                    catch (NumberFormatException ignored) {}
                }
            });
            fields.add(zf);
        }
    }
}
