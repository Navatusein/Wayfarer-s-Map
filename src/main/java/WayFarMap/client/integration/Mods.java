package WayFarMap.client.integration;

import cpw.mods.fml.common.Loader;

/** Optional mods. Integration classes may only be touched after checking these. */
public final class Mods {

    private static Boolean visualProspecting;

    private Mods() {}

    public static boolean isVisualProspectingLoaded() {
        if (visualProspecting == null) {
            visualProspecting = Loader.isModLoaded("visualprospecting");
        }
        return visualProspecting;
    }
}
