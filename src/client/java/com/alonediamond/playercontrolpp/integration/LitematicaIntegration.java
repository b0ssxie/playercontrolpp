package com.alonediamond.playercontrolpp.integration;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Litematica integration via reflection.
 * Calls moveLayer() exactly as Litematica's PageUp/PageDown hotkeys do.
 */
public class LitematicaIntegration {

    private static boolean showActionBar = true;

    public static boolean isShowActionBar() { return showActionBar; }
    public static void setShowActionBar(boolean show) { showActionBar = show; }

    public static boolean incrementLayer(int amount) {
        if (amount == 0) return false;

        try {
            // DataManager.getRenderLayerRange()
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object range = dmClass.getMethod("getRenderLayerRange").invoke(null);
            if (range == null) return false;

            // Only SINGLE_LAYER mode
            Object mode = range.getClass().getMethod("getLayerMode").invoke(range);
            if (!"SINGLE_LAYER".equals(((Enum<?>) mode).name())) return false;

            // range.moveLayer(amount) — exactly what PageUp/PageDown triggers
            boolean ok = (Boolean) range.getClass()
                    .getMethod("moveLayer", int.class).invoke(range, amount);
            if (!ok) return false;

            if (showActionBar) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    String layerStr = (String) range.getClass()
                            .getMethod("getCurrentLayerString").invoke(range);
                    String msg = StringUtils.translate("playercontrolpp.message.litematica.layer", layerStr);
                    client.player.sendMessage(Text.of(msg), true);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
