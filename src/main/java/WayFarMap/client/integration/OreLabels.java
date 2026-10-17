package WayFarMap.client.integration;

import WayFarMap.Config;

/**
 * The mod's settings for ore vein names, for {@link ProspectingLayer}: there {@code Config} is VisualProspecting's and
 * {@code WayFarMap} the mod's main class, so the mod's own settings are read here.
 */
final class OreLabels {

    private OreLabels() {}

    /** Names of all veins that fit (not only the one under the mouse). */
    static boolean always() {
        return Config.oreLabels == Config.LABELS_ALWAYS;
    }

    /** Longest name, in pixels. */
    static int maxWidth() {
        return Config.oreLabelMaxWidth;
    }
}
