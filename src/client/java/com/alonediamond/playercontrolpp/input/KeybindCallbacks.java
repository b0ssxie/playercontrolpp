package com.alonediamond.playercontrolpp.input;

import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.QuickTurnFeature;
import com.alonediamond.playercontrolpp.gui.RouteListGui;
import com.alonediamond.playercontrolpp.route.RouteManager;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.MinecraftClient;

import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.AUTO_FORWARD;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.QUICK_TURN;
import static com.alonediamond.playercontrolpp.config.Configs.Hotkeys.OPEN_ROUTE_GUI;

public class KeybindCallbacks {

    public static void register() {
        AUTO_FORWARD.getKeybind().setCallback(new AutoForwardCallback());
        QUICK_TURN.getKeybind().setCallback(new QuickTurnCallback());
        OPEN_ROUTE_GUI.getKeybind().setCallback(new OpenRouteGuiCallback());

        // Register route hotkey callbacks
        for (RouteManager.RouteHotkey rh : RouteManager.getInstance().getRouteHotkeyList()) {
            rh.getKeybind().setCallback(new RouteToggleCallback(rh));
        }
    }

    private static class AutoForwardCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            AutoForwardFeature.toggle(client);
            return true;
        }
    }

    private static class QuickTurnCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            QuickTurnFeature.execute(client);
            return true;
        }
    }

    private static class OpenRouteGuiCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            client.setScreen(new RouteListGui(null));
            return true;
        }
    }

    private static class RouteToggleCallback implements IHotkeyCallback {
        private final RouteManager.RouteHotkey routeHotkey;

        RouteToggleCallback(RouteManager.RouteHotkey routeHotkey) {
            this.routeHotkey = routeHotkey;
        }

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            RouteManager.getInstance().getRoutes().stream()
                    .filter(r -> r.getId().equals(routeHotkey.getRoute().getId()))
                    .findFirst()
                    .ifPresent(r -> com.alonediamond.playercontrolpp.route.RouteFlowRuntime
                            .getInstance().toggleRoute(r));
            return true;
        }
    }
}
