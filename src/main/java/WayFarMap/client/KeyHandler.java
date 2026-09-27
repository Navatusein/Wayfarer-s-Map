package WayFarMap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import WayFarMap.Config;
import WayFarMap.client.gui.GuiWorldMap;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class KeyHandler {

    private static final String CATEGORY = "key.categories.wayfarmap";

    public static final KeyBinding OPEN_MAP = new KeyBinding("key.wayfarmap.open_map", Keyboard.KEY_M, CATEGORY);
    public static final KeyBinding TOGGLE_MINIMAP = new KeyBinding(
        "key.wayfarmap.toggle_minimap",
        Keyboard.KEY_N,
        CATEGORY);
    public static final KeyBinding ZOOM_IN = new KeyBinding("key.wayfarmap.zoom_in", Keyboard.KEY_EQUALS, CATEGORY);
    public static final KeyBinding ZOOM_OUT = new KeyBinding("key.wayfarmap.zoom_out", Keyboard.KEY_MINUS, CATEGORY);

    public void register() {
        ClientRegistry.registerKeyBinding(OPEN_MAP);
        ClientRegistry.registerKeyBinding(TOGGLE_MINIMAP);
        ClientRegistry.registerKeyBinding(ZOOM_IN);
        ClientRegistry.registerKeyBinding(ZOOM_OUT);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.theWorld == null) {
            return;
        }
        if (OPEN_MAP.isPressed()) {
            mc.displayGuiScreen(new GuiWorldMap());
        }
        if (TOGGLE_MINIMAP.isPressed()) {
            Config.setMinimapEnabled(!Config.minimapEnabled);
        }
        if (ZOOM_IN.isPressed()) {
            Config.setMinimapZoom(Config.minimapZoom + 1);
        }
        if (ZOOM_OUT.isPressed()) {
            Config.setMinimapZoom(Config.minimapZoom - 1);
        }
    }
}
