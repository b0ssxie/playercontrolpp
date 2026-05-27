package com.alonediamond.playercontrolpp.event;

import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

public class ClientEventHandler {

    public static void register() {
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(new WorldLoadListener());
        TickHandler.getInstance().registerClientTickHandler(new RouteTickHandler());
    }

    private static class WorldLoadListener implements IWorldLoadListener {
        @Override
        public void onWorldLoadPre(ClientWorld world1, ClientWorld world2, MinecraftClient client) {
            AutoForwardFeature.onWorldChange();
            RouteFlowRuntime.getInstance().onWorldChange();
        }
    }

    private static class RouteTickHandler implements IClientTickHandler {
        @Override
        public void onClientTick(MinecraftClient mc) {
            RouteFlowRuntime.getInstance().onClientTick(mc);
        }
    }
}
