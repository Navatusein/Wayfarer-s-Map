package WayFarMap.client.gui.ui;

/** Minimal pixel icons for the world map's buttons, drawn with rectangles in the theme's colors. */
public final class Icons {

    private Icons() {}

    public static final String[] WAYPOINTS = { // a flag
        ".#.........", ".########..", ".#########.", ".########..", ".#.........", ".#.........", ".#.........",
        ".#.........", "###........" };

    public static final String[] STATS = { // a bar chart
        "........##.", "........##.", "....##..##.", "....##..##.", "##..##..##.", "##..##..##.", "##..##..##.",
        "##..##..##.", "###########" };

    public static final String[] SETTINGS = { // a gear
        ".....#.....", "..#.###.#..", "...#####...", "..###.###..", ".###...###.", "####...####", ".###...###.",
        "..###.###..", "...#####...", "..#.###.#..", ".....#....." };

    public static final String[] ADDONS = { // stacked layers
        "....###....", "..##...##..", "##.......##", "..##...##..", "....###....", "#.........#", ".##.....##.",
        "...##.##...", ".....#....." };

    public static final String[] GRID = { "##########", "#..#..#..#", "#..#..#..#", "##########", "#..#..#..#",
        "#..#..#..#", "##########", "#..#..#..#", "#..#..#..#", "##########" };

    public static final String[] BIOMES = { // a tree
        "....##....", "...####...", "..######..", ".########.", "..######..", ".########.", "##########", "....##....",
        "....##....", "...####..." };

    public static final String[] TOPO = { // contour lines around a peak
        "...####...", "..#....#..", ".#..##..#.", "#..#..#..#", "#..#..#..#", ".#..##..#.", "..#....#..",
        "...####..." };

    public static final String[] CAVES = { // a hill with a tunnel
        "....##....", "...####...", "..######..", ".###..###.", ".##....##.", "##......##", "##......##",
        "##########" };

    public static final String[] DAY = { // the sun
        ".....#.....", ".#...#...#.", "..#.....#..", "....###....", "...#####...", "##.#####.##", "...#####...",
        "....###....", "..#.....#..", ".#...#...#.", ".....#....." };

    public static final String[] NIGHT = { // the moon and a star
        "...####...#", "..###.....#", ".###......#", ".##........", "###........", "###........", "###........",
        ".##.....#..", ".###.......", "..###......", "...####...." };

    public static final String[] DAY_NIGHT = { // a circle, half light and half dark
        "...#####...", "..#..####..", ".#...#####.", "#....######", "#....######", "#....######", "#....######",
        "#....######", ".#...#####.", "..#..####..", "...#####..." };

    public static final String[] MOBS = { // a creeper face
        "##########", "#........#", "#.##..##.#", "#.##..##.#", "#...##...#", "#..####..#", "#..####..#", "#..#..#..#",
        "#........#", "##########" };

    public static final String[] TEAM = { // two people
        "..##....##.", ".####..####", ".####..####", "..##....##.", "...........", ".####..####", "###########",
        "###########", "###########" };

    public static final String[] ISO = { // a block seen from above a corner
        "....###....", "..##...##..", "##.......##", "#.##...##.#", "#...###...#", "#....#....#", "#....#....#",
        "##...#...##", "..##.#.##..", "....###...." };

    public static final String[] CAMERA = { // a camera
        "...###.....", "###########", "#.........#", "#...###...#", "#..#...#..#", "#..#...#..#", "#...###...#",
        "#.........#", "###########" };

    public static final String[] FOLLOW = { // a crosshair on the player
        ".....#.....", "...#####...", "..#..#..#..", ".#...#...#.", ".#..###..#.", "#####.#####", ".#..###..#.",
        ".#...#...#.", "..#..#..#..", "...#####...", ".....#....." };

    public static final String[] CLEAN = { // a bin
        "...###...", "#########", ".........", ".#######.", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.",
        ".#######." };

    public static final String[] CLOSE = { // a cross
        "##.....##", ".##...##.", "..##.##..", "...###...", "...###...", "..##.##..", ".##...##.", "##.....##" };

    public static final String[] HELP = { // a question mark
        "..#####..", ".##...##.", ".......##", "......##.", "....###..", "....##...", ".........", "....##...",
        "....##..." };

    public static int width(String[] icon) {
        int width = 0;
        for (String row : icon) {
            width = Math.max(width, row.length());
        }
        return width;
    }

    /** Draws the icon with its top left corner at (x, y), one GUI pixel per '#', merging runs into rectangles. */
    public static void draw(String[] icon, int x, int y, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean filled = col < line.length() && line.charAt(col) == '#';
                if (filled && start < 0) {
                    start = col;
                } else if (!filled && start >= 0) {
                    Theme.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }
}
