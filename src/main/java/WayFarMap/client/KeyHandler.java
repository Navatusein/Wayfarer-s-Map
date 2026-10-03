package WayFarMap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;

import WayFarMap.Config;
import WayFarMap.client.gui.GuiEditWaypoint;
import WayFarMap.client.gui.GuiWaypointList;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
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

    // Map views and layers (shared by the minimap and the world map): no key until the player sets one.
    public static final KeyBinding MODE_BLOCKS = unbound("mode_blocks");
    public static final KeyBinding MODE_PLANTLESS = unbound("mode_plantless");
    public static final KeyBinding MODE_TOPO = unbound("mode_topo");
    public static final KeyBinding MODE_BIOMES = unbound("mode_biomes");
    public static final KeyBinding ORES = unbound("ores");
    public static final KeyBinding FLUIDS = unbound("fluids");
    public static final KeyBinding CLAIMS = unbound("claims");
    public static final KeyBinding POWERFAILS = unbound("powerfails");
    public static final KeyBinding NODES = unbound("nodes");
    public static final KeyBinding CHUNK_GRID = unbound("chunk_grid");
    public static final KeyBinding HOSTILE_MOBS = unbound("hostile_mobs");
    public static final KeyBinding PASSIVE_MOBS = unbound("passive_mobs");
    public static final KeyBinding FRIENDLY_MOBS = unbound("friendly_mobs");
    public static final KeyBinding PETS = unbound("pets");
    public static final KeyBinding PLAYERS = unbound("players");
    public static final KeyBinding LIGHT = unbound("light");

    private static final KeyBinding[] UNBOUND = { MODE_BLOCKS, MODE_PLANTLESS, MODE_TOPO, MODE_BIOMES, ORES, FLUIDS,
        CLAIMS, POWERFAILS, NODES, CHUNK_GRID, HOSTILE_MOBS, PASSIVE_MOBS, FRIENDLY_MOBS, PETS, PLAYERS, LIGHT };

    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };
    private static final String[] LIGHT_MODE_KEYS = { "auto", "day", "night" };
    /** Chat line of these keys: each message replaces the one before instead of filling the chat. */
    private static final int CHAT_LINE = 0x57466D70;

    /**
     * The key (or mouse button) one of the mod's bindings is set to now, as the controls screen names it; null if it
     * has none. {@code name} is the binding's name without {@code key.wayfarmap.} ({@code open_map}).
     */
    public static String keyName(String name) {
        String description = "key.wayfarmap." + name;
        KeyBinding[] all = Minecraft.getMinecraft().gameSettings.keyBindings;
        if (all != null) {
            for (KeyBinding binding : all) {
                if (binding != null && description.equals(binding.getKeyDescription())) {
                    int code = binding.getKeyCode();
                    return code == Keyboard.KEY_NONE ? null : GameSettings.getKeyDisplayString(code);
                }
            }
        }
        return null;
    }

    private static KeyBinding unbound(String name) {
        return new KeyBinding("key.wayfarmap." + name, Keyboard.KEY_NONE, CATEGORY);
    }

    public void register() {
        ClientRegistry.registerKeyBinding(OPEN_MAP);
        ClientRegistry.registerKeyBinding(TOGGLE_MINIMAP);
        ClientRegistry.registerKeyBinding(ZOOM_IN);
        ClientRegistry.registerKeyBinding(ZOOM_OUT);
        ClientRegistry.registerKeyBinding(NEW_WAYPOINT);
        ClientRegistry.registerKeyBinding(WAYPOINT_LIST);
        ClientRegistry.registerKeyBinding(CAVE_MODE);
        for (KeyBinding key : UNBOUND) {
            ClientRegistry.registerKeyBinding(key);
        }
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
        viewKeys(mc);
    }

    private static void viewKeys(Minecraft mc) {
        if (MODE_BLOCKS.isPressed()) {
            Config.mapDisplayMode = Config.DISPLAY_BLOCKS;
            Config.setShowPlants(true);
            tell(mc, MODE_BLOCKS, null);
        }
        if (MODE_PLANTLESS.isPressed()) {
            Config.mapDisplayMode = Config.DISPLAY_BLOCKS;
            Config.setShowPlants(false);
            tell(mc, MODE_PLANTLESS, null);
        }
        if (MODE_TOPO.isPressed()) {
            Config.toggleTopoView();
            tell(mc, MODE_TOPO, Config.mapDisplayMode == Config.DISPLAY_TOPO);
        }
        if (MODE_BIOMES.isPressed()) {
            Config.toggleBiomeView();
            tell(mc, MODE_BIOMES, Config.mapDisplayMode == Config.DISPLAY_BIOMES);
        }
        if (ORES.isPressed() && installed(mc, ORES, Mods.isVisualProspectingLoaded())) {
            Config.toggleOreVeins();
            tell(mc, ORES, Config.showOreVeins);
        }
        if (FLUIDS.isPressed() && installed(mc, FLUIDS, Mods.isVisualProspectingLoaded())) {
            Config.toggleUndergroundFluids();
            tell(mc, FLUIDS, Config.showUndergroundFluids);
        }
        if (CLAIMS.isPressed() && installed(mc, CLAIMS, Mods.isClaimsAvailable())) {
            Config.toggleClaims();
            if (Config.showClaims) {
                ClaimsLayer.onShow();
            }
            tell(mc, CLAIMS, Config.showClaims);
        }
        if (POWERFAILS.isPressed() && installed(mc, POWERFAILS, Mods.isPowerfailsAvailable())) {
            Config.togglePowerfails();
            tell(mc, POWERFAILS, Config.showPowerfails);
        }
        if (NODES.isPressed() && installed(mc, NODES, Mods.isThaumcraftNodesAvailable())) {
            Config.toggleThaumcraftNodes();
            tell(mc, NODES, Config.showThaumcraftNodes);
        }
        if (CHUNK_GRID.isPressed()) {
            Config.toggleChunkGrid();
            tell(mc, CHUNK_GRID, Config.chunkGrid);
        }
        if (HOSTILE_MOBS.isPressed()) {
            Config.toggleHostileMobs();
            tell(mc, HOSTILE_MOBS, Config.showHostileMobs);
        }
        if (PASSIVE_MOBS.isPressed()) {
            Config.toggleNeutralMobs();
            tell(mc, PASSIVE_MOBS, Config.showPassiveMobs);
        }
        if (FRIENDLY_MOBS.isPressed()) {
            Config.toggleFriendlyMobs();
            tell(mc, FRIENDLY_MOBS, Config.showOtherEntities);
        }
        if (PETS.isPressed()) {
            Config.togglePets();
            tell(mc, PETS, Config.showPets);
        }
        if (PLAYERS.isPressed()) {
            Config.toggleOtherPlayers();
            tell(mc, PLAYERS, Config.showOtherPlayers);
        }
        if (LIGHT.isPressed()) {
            Config.setMapLightMode((Config.mapLightMode + 1) % LIGHT_MODE_KEYS.length);
            say(
                mc,
                I18n.format("wayfarmap.option.map.lightMode") + ": "
                    + I18n.format("wayfarmap.option.map.lightMode." + LIGHT_MODE_KEYS[Config.mapLightMode]));
        }
    }

    /** False, with a message, if the mod the layer comes from isn't installed. */
    private static boolean installed(Minecraft mc, KeyBinding key, boolean available) {
        if (!available) {
            say(mc, I18n.format(key.getKeyDescription()) + ": " + I18n.format("key.wayfarmap.not_installed"));
        }
        return available;
    }

    /** The key's name, and on or off (nothing for a mode that is only set). */
    private static void tell(Minecraft mc, KeyBinding key, Boolean on) {
        String text = I18n.format(key.getKeyDescription());
        if (on != null) {
            text += ": " + I18n.format(on ? "options.on" : "options.off");
        }
        say(mc, text);
    }

    private static void say(Minecraft mc, String text) {
        IChatComponent message = new ChatComponentText(text);
        mc.ingameGUI.getChatGUI()
            .printChatMessageWithOptionalDeletion(message, CHAT_LINE);
    }
}
