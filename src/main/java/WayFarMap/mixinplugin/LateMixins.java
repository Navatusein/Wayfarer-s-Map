package WayFarMap.mixinplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

/** Mixins into other mods, applied only when those mods are installed. */
@LateMixin
public class LateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.wayfarmap.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("OpenBlocks") && FMLLaunchHandler.side()
            .isClient()) {
            // The player's grave going away takes its death waypoint with it (the client's own world only).
            mixins.add("openblocks.MixinTileEntityGrave");
        }
        return mixins;
    }
}
