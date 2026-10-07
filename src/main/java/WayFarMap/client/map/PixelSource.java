package WayFarMap.client.map;

/** A square of map pixels with their extra bytes: a full region or its reduced copy. */
interface PixelSource {

    /** Width and height in pixels. */
    int size();

    /** ARGB pixel; alpha 0 = not explored. */
    int getPixel(int localX, int localZ);

    /** Extra byte of the pixel (height or biome), 0 if unknown. */
    int getExtra(int localX, int localZ);

    /** Light byte of the pixel: block light in the low 4 bits, the topography flags above (MapRegion.TOPO_*). */
    int getLight(int localX, int localZ);

    /** Grows on every change, so images derived from it know when to rebuild. */
    int getChanges();
}
