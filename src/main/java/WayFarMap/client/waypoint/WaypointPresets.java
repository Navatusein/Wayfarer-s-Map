package WayFarMap.client.waypoint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import WayFarMap.WayFarMap;

/**
 * Presets of quick waypoints: the look of a waypoint (name, icon, outline, beam, group) kept to put down again with a
 * click on the map, only where it is new (ore veins, miners). The same in every world, saved to
 * {@code wayfarmap/waypoint_presets.json}.
 */
public final class WaypointPresets {

    public static final WaypointPresets INSTANCE = new WaypointPresets();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private List<Waypoint> presets;

    private WaypointPresets() {}

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "wayfarmap"), "waypoint_presets.json");
    }

    /** The presets, in their order; read from the file on first use. */
    public List<Waypoint> all() {
        if (presets == null) {
            presets = new ArrayList<>();
            File file = file();
            if (file.isFile()) {
                try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    List<Waypoint> read = GSON.fromJson(reader, new TypeToken<List<Waypoint>>() {}.getType());
                    if (read != null) {
                        for (Waypoint preset : read) {
                            if (preset != null) {
                                if (preset.name == null) {
                                    preset.name = "";
                                }
                                presets.add(preset);
                            }
                        }
                    }
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Could not read waypoint presets from " + file, e);
                }
            }
        }
        return Collections.unmodifiableList(presets);
    }

    /** Adds the preset at the end, or keeps the change made to one already there; saves. */
    public void put(Waypoint preset) {
        all();
        if (!presets.contains(preset)) {
            presets.add(preset);
        }
        save();
    }

    public void remove(Waypoint preset) {
        all();
        presets.remove(preset);
        save();
    }

    private void save() {
        File file = file();
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(presets, writer);
            }
            if (file.exists() && !file.delete()) {
                WayFarMap.LOG.warn("Could not replace " + file);
            }
            if (!tmp.renameTo(file)) {
                WayFarMap.LOG.warn("Could not rename " + tmp + " to " + file);
            }
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not save waypoint presets to " + file, e);
        }
    }

    /**
     * Puts down a waypoint of the preset at the block, in the dimension: its look, name and group, the player's own and
     * shown; shared with the team if its group shares its new ones. Returns it.
     */
    public static Waypoint place(Waypoint preset, int x, int y, int z, int dimension) {
        Waypoint waypoint = preset.copy();
        waypoint.x = x;
        waypoint.y = y;
        waypoint.z = z;
        waypoint.dimension = dimension;
        waypoint.enabled = true;
        waypoint.death = false;
        waypoint.diedAt = 0;
        waypoint.makeOwn();
        if (waypoint.name == null || waypoint.name.trim()
            .isEmpty()) {
            waypoint.name = x + ", " + y + ", " + z;
        }
        WaypointManager manager = WaypointManager.INSTANCE;
        if (waypoint.group != null) {
            // The group the preset puts its waypoints in, made in this world if it isn't there yet.
            WaypointGroup group = manager.createGroup(waypoint.group);
            waypoint.group = group.name;
            if (group.shareNew && TeamWaypoints.INSTANCE.isAvailable()) {
                WaypointManager.setShared(waypoint, true);
            }
        }
        manager.addWaypoint(waypoint);
        return waypoint;
    }
}
