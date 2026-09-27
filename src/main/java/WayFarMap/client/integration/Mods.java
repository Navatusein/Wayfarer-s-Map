package WayFarMap.client.integration;

import cpw.mods.fml.common.Loader;

/** Optional mods. Integration classes may only be touched after checking these. */
public final class Mods {

    private static Boolean visualProspecting;
    private static Boolean claims;

    private Mods() {}

    /**
     * ServerUtilities chunk claims. Its map protocol lives in its Navigator integration, whose client handler
     * needs Navigator, so both have to be installed (as in GTNH).
     */
    public static boolean isClaimsAvailable() {
        if (claims == null) {
            claims = Loader.isModLoaded("serverutilities") && Loader.isModLoaded("navigator");
        }
        return claims;
    }

    /** GregTech power failure markers (GT5-Unofficial versions that have them); see {@link PowerfailLayer}. */
    public static boolean isPowerfailsAvailable() {
        return Loader.isModLoaded("gregtech") && PowerfailLayer.isAvailable();
    }

    public static boolean isVisualProspectingLoaded() {
        if (visualProspecting == null) {
            visualProspecting = Loader.isModLoaded("visualprospecting");
        }
        return visualProspecting;
    }
}
