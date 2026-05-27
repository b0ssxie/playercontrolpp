package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.route.Route;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
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
    private static final int ITEM_HEIGHT = 22;
    private static final int COLUMN_LEFT = 10;
    private static final int COLUMN_WIDTH = 300;
    private static final int DETAILS_LEFT = 320;
    private static final int EDIT_TOP = 50;

    private final Screen parent;
    private Route selectedRoute;
    private int scrollOffset;

    // Edit fields
    private TextFieldWidget nameField;
    private TextFieldWidget startXField, startYField, startZField;
    private TextFieldWidget endXField, endYField, endZField;
    private TextFieldWidget radiusField;
    private TextFieldWidget loopField;
    private boolean dirty;

    public RouteListGui(Screen parent) {
        super(Text.of("Route Flow System"));
        this.parent = parent;
    }

    @Override
    public void close() {
        if (dirty) {
            RouteManager.getInstance().saveRoutes();
        }
        if (parent != null) {
            MinecraftClient.getInstance().setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    protected void init() {
        super.init();
        int w = this.width;
        int h = this.height;
        this.scrollOffset = 0;

        // Add Route button
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.add")),
                btn -> {
                    Route route = RouteManager.getInstance().addRoute(
                            StringUtils.translate("playercontrolpp.gui.route.new_route"));
                    selectedRoute = route;
                    dirty = true;
                    refreshEditFields();
                })
                .dimensions(COLUMN_LEFT, TOP, 140, 20)
                .build());

        // Remove Route button
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.remove")),
                btn -> {
                    if (selectedRoute != null) {
                        RouteManager.getInstance().removeRoute(selectedRoute);
                        selectedRoute = null;
                        dirty = true;
                        refreshEditFields();
                    }
                })
                .dimensions(COLUMN_LEFT + 150, TOP, 100, 20)
                .build());

        // Back button
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.back")),
                btn -> close())
                .dimensions(w - 60, 10, 50, 20)
                .build());

        // Create edit fields
        int fieldX = DETAILS_LEFT + 10;
        int fieldW = 100;

        nameField = new TextFieldWidget(textRenderer, fieldX, EDIT_TOP + 0, 160, 20, Text.empty());
        nameField.setChangedListener(s -> { if (selectedRoute != null) {
            selectedRoute.setName(s);
            dirty = true;
        }});
        this.addSelectableChild(nameField);

        startXField = new TextFieldWidget(textRenderer, fieldX, EDIT_TOP + 50, fieldW, 20, Text.empty());
        startYField = new TextFieldWidget(textRenderer, fieldX + 110, EDIT_TOP + 50, fieldW, 20, Text.empty());
        startZField = new TextFieldWidget(textRenderer, fieldX + 220, EDIT_TOP + 50, fieldW, 20, Text.empty());
        this.addSelectableChild(startXField);
        this.addSelectableChild(startYField);
        this.addSelectableChild(startZField);

        endXField = new TextFieldWidget(textRenderer, fieldX, EDIT_TOP + 100, fieldW, 20, Text.empty());
        endYField = new TextFieldWidget(textRenderer, fieldX + 110, EDIT_TOP + 100, fieldW, 20, Text.empty());
        endZField = new TextFieldWidget(textRenderer, fieldX + 220, EDIT_TOP + 100, fieldW, 20, Text.empty());
        this.addSelectableChild(endXField);
        this.addSelectableChild(endYField);
        this.addSelectableChild(endZField);

        radiusField = new TextFieldWidget(textRenderer, fieldX, EDIT_TOP + 150, 60, 20, Text.empty());
        radiusField.setChangedListener(s -> { if (selectedRoute != null) try {
            selectedRoute.setArrivalRadius(Double.parseDouble(s));
            dirty = true;
        } catch (NumberFormatException ignored) {}});
        this.addSelectableChild(radiusField);

        loopField = new TextFieldWidget(textRenderer, fieldX + 100, EDIT_TOP + 150, 60, 20, Text.empty());
        loopField.setChangedListener(s -> { if (selectedRoute != null) try {
            selectedRoute.setLoopCount(Integer.parseInt(s));
            dirty = true;
        } catch (NumberFormatException ignored) {}});
        this.addSelectableChild(loopField);

        // Set Start button
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.set_start")),
                btn -> {
                    var player = MinecraftClient.getInstance().player;
                    if (player != null && selectedRoute != null) {
                        selectedRoute.setStartPos(player.getX(), player.getY(), player.getZ());
                        selectedRoute.setDimension(player.getWorld().getRegistryKey());
                        dirty = true;
                        refreshEditFields();
                    }
                })
                .dimensions(fieldX, EDIT_TOP + 50 - 20, 60, 18)
                .build());

        // Set End button
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.set_end")),
                btn -> {
                    var player = MinecraftClient.getInstance().player;
                    if (player != null && selectedRoute != null) {
                        selectedRoute.setEndPos(player.getX(), player.getY(), player.getZ());
                        dirty = true;
                        refreshEditFields();
                    }
                })
                .dimensions(fieldX, EDIT_TOP + 100 - 20, 60, 18)
                .build());

        refreshEditFields();
    }

    private void refreshEditFields() {
        boolean hasSelection = selectedRoute != null;
        nameField.setEditable(hasSelection);
        startXField.setEditable(hasSelection);
        startYField.setEditable(hasSelection);
        startZField.setEditable(hasSelection);
        endXField.setEditable(hasSelection);
        endYField.setEditable(hasSelection);
        endZField.setEditable(hasSelection);
        radiusField.setEditable(hasSelection);
        loopField.setEditable(hasSelection);

        if (hasSelection) {
            nameField.setText(selectedRoute.getName());
            startXField.setText(String.format("%.2f", selectedRoute.getStartPos().x));
            startYField.setText(String.format("%.2f", selectedRoute.getStartPos().y));
            startZField.setText(String.format("%.2f", selectedRoute.getStartPos().z));
            endXField.setText(String.format("%.2f", selectedRoute.getEndPos().x));
            endYField.setText(String.format("%.2f", selectedRoute.getEndPos().y));
            endZField.setText(String.format("%.2f", selectedRoute.getEndPos().z));
            radiusField.setText(String.format("%.1f", selectedRoute.getArrivalRadius()));
            loopField.setText(String.valueOf(selectedRoute.getLoopCount()));
        } else {
            nameField.setText("");
            startXField.setText(""); startYField.setText(""); startZField.setText("");
            endXField.setText(""); endYField.setText(""); endZField.setText("");
            radiusField.setText("");
            loopField.setText("");
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.title")),
                width / 2, 15, 0xFFFFFFFF);

        // Render route list
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int listTop = TOP + 30;
        int maxVisible = (height - listTop - 10) / ITEM_HEIGHT;
        if (scrollOffset < 0) scrollOffset = 0;

        for (int i = scrollOffset; i < Math.min(routes.size(), scrollOffset + maxVisible); i++) {
            int y = listTop + (i - scrollOffset) * ITEM_HEIGHT;
            Route route = routes.get(i);
            boolean isSelected = route == selectedRoute;
            int color = isSelected ? 0xFF55FF55 : 0xFFAAAAAA;
            String text = (route.isEnabled() ? "[X] " : "[ ] ") + route.getName();
            context.drawTextWithShadow(textRenderer, Text.of(text), COLUMN_LEFT + 4, y + 6, color);

            // Click detection is handled in mouseClicked
        }

        // Render details panel
        if (selectedRoute != null) {
            int dx = DETAILS_LEFT;
            int dy = EDIT_TOP - 5;
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                    dx, dy, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.start_pos") + ":"),
                    dx, dy + 50, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.end_pos") + ":"),
                    dx, dy + 100, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.arrival_radius") + ":"),
                    dx, dy + 150, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.loop_count") + ":"),
                    dx + 100, dy + 150, 0xFFFFFFFF);

            // Dimension info
            if (!selectedRoute.getDimensionId().isEmpty()) {
                context.drawTextWithShadow(textRenderer,
                        Text.of("Dim: " + selectedRoute.getDimensionId()),
                        dx, dy + 180, 0xFF888888);
            }
        }

        // Render edit fields
        nameField.render(context, mouseX, mouseY, delta);
        if (selectedRoute != null) {
            startXField.render(context, mouseX, mouseY, delta);
            startYField.render(context, mouseX, mouseY, delta);
            startZField.render(context, mouseX, mouseY, delta);
            endXField.render(context, mouseX, mouseY, delta);
            endYField.render(context, mouseX, mouseY, delta);
            endZField.render(context, mouseX, mouseY, delta);
            radiusField.render(context, mouseX, mouseY, delta);
            loopField.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Route list clicks
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int listTop = TOP + 30;
        int maxVisible = (height - listTop - 10) / ITEM_HEIGHT;

        for (int i = scrollOffset; i < Math.min(routes.size(), scrollOffset + maxVisible); i++) {
            int y = listTop + (i - scrollOffset) * ITEM_HEIGHT;
            if (mouseX >= COLUMN_LEFT && mouseX <= COLUMN_LEFT + COLUMN_WIDTH
                    && mouseY >= y && mouseY <= y + ITEM_HEIGHT) {
                selectedRoute = routes.get(i);
                refreshEditFields();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int maxVisible = (height - (TOP + 30) - 10) / ITEM_HEIGHT;
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) verticalAmount,
                Math.max(0, routes.size() - maxVisible)));
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (nameField.isFocused()) return nameField.charTyped(chr, modifiers);
        if (startXField.isFocused()) return startXField.charTyped(chr, modifiers);
        if (startYField.isFocused()) return startYField.charTyped(chr, modifiers);
        if (startZField.isFocused()) return startZField.charTyped(chr, modifiers);
        if (endXField.isFocused()) return endXField.charTyped(chr, modifiers);
        if (endYField.isFocused()) return endYField.charTyped(chr, modifiers);
        if (endZField.isFocused()) return endZField.charTyped(chr, modifiers);
        if (radiusField.isFocused()) return radiusField.charTyped(chr, modifiers);
        if (loopField.isFocused()) return loopField.charTyped(chr, modifiers);
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameField.isFocused()) return nameField.keyPressed(keyCode, scanCode, modifiers);
        if (startXField.isFocused()) return startXField.keyPressed(keyCode, scanCode, modifiers);
        if (startYField.isFocused()) return startYField.keyPressed(keyCode, scanCode, modifiers);
        if (startZField.isFocused()) return startZField.keyPressed(keyCode, scanCode, modifiers);
        if (endXField.isFocused()) return endXField.keyPressed(keyCode, scanCode, modifiers);
        if (endYField.isFocused()) return endYField.keyPressed(keyCode, scanCode, modifiers);
        if (endZField.isFocused()) return endZField.keyPressed(keyCode, scanCode, modifiers);
        if (radiusField.isFocused()) return radiusField.keyPressed(keyCode, scanCode, modifiers);
        if (loopField.isFocused()) return loopField.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
