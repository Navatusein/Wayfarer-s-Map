package WayFarMap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;

import WayFarMap.Config;
import WayFarMap.client.gui.GuiEditWaypoint;
import WayFarMap.client.gui.GuiWaypointList;
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
    public static final KeyBinding NEW_WAYPOINT = new KeyBinding(
        "key.wayfarmap.new_waypoint",
        Keyboard.KEY_B,
        CATEGORY);
    public static final KeyBinding CAVE_MODE = new KeyBinding("key.wayfarmap.cave_mode", Keyboard.KEY_K, CATEGORY);
    public static final KeyBinding WAYPOINT_LIST = new KeyBinding(
        "key.wayfarmap.waypoint_list",
        Keyboard.KEY_U,
        CATEGORY);

    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };

    public void register() {
        ClientRegistry.registerKeyBinding(OPEN_MAP);
        ClientRegistry.registerKeyBinding(TOGGLE_MINIMAP);
        ClientRegistry.registerKeyBinding(ZOOM_IN);
        ClientRegistry.registerKeyBinding(ZOOM_OUT);
        ClientRegistry.registerKeyBinding(NEW_WAYPOINT);
        ClientRegistry.registerKeyBinding(WAYPOINT_LIST);
        ClientRegistry.registerKeyBinding(CAVE_MODE);
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
        if (NEW_WAYPOINT.isPressed() && mc.thePlayer != null) {
            mc.displayGuiScreen(
                GuiEditWaypoint.create(
                    null,
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.boundingBox.minY),
                    MathHelper.floor_double(mc.thePlayer.posZ),
                    mc.theWorld.provider.dimensionId));
        }
        if (CAVE_MODE.isPressed()) {
            Config.cycleCaveMode();
            mc.ingameGUI.getChatGUI()
                .printChatMessage(
                    new ChatComponentText(
                        I18n.format("wayfarmap.option.map.caveMode") + ": "
                            + I18n.format("wayfarmap.option.map.caveMode." + CAVE_MODE_KEYS[Config.caveMode])));
        }
        if (WAYPOINT_LIST.isPressed()) {
            mc.displayGuiScreen(new GuiWaypointList(null));
        }
    }
}
