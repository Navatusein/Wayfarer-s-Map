package WayFarMap.client.gui.ui;

/** Small flags of the mod's languages ({@code Lang.CODES}), drawn from rectangles. */
public final class Flags {

    public static final int WIDTH = 18, HEIGHT = 12;

    /** The big star of China's flag, 5x5. */
    private static final String[] CHINA_STAR = { "..#..", "#####", ".###.", ".###.", "##.##" };

    private Flags() {}

    /** The flag of the language at (x, y), {@link #WIDTH} by {@link #HEIGHT}. */
    public static void draw(String language, int x, int y) {
        switch (language) {
            case "ru_RU":
                Theme.fill(x, y, x + WIDTH, y + 4, 0xFFFFFFFF);
                Theme.fill(x, y + 4, x + WIDTH, y + 8, 0xFF0039A6);
                Theme.fill(x, y + 8, x + WIDTH, y + 12, 0xFFD52B1E);
                break;
            case "uk_UA":
                Theme.fill(x, y, x + WIDTH, y + 6, 0xFF0057B7);
                Theme.fill(x, y + 6, x + WIDTH, y + 12, 0xFFFFD700);
                break;
            case "zh_CN":
                Theme.fill(x, y, x + WIDTH, y + HEIGHT, 0xFFEE1C25);
                for (int row = 0; row < CHINA_STAR.length; row++) {
                    for (int col = 0; col < CHINA_STAR[row].length(); col++) {
                        if (CHINA_STAR[row].charAt(col) == '#') {
                            dot(x + 1 + col, y + 1 + row, 0xFFFFDE00);
                        }
                    }
                }
                // The four small stars in an arc around it.
                dot(x + 7, y + 1, 0xFFFFDE00);
                dot(x + 8, y + 2, 0xFFFFDE00);
                dot(x + 8, y + 4, 0xFFFFDE00);
                dot(x + 7, y + 5, 0xFFFFDE00);
                break;
            default:
                // The USA: red and white stripes, a blue field with white stars.
                for (int row = 0; row < HEIGHT; row++) {
                    Theme.fill(x, y + row, x + WIDTH, y + row + 1, row % 2 == 0 ? 0xFFB22234 : 0xFFFFFFFF);
                }
                Theme.fill(x, y, x + 8, y + 7, 0xFF3C3B6E);
                for (int row = 1; row < 7; row += 2) {
                    for (int col = row % 4 == 1 ? 1 : 2; col < 8; col += 2) {
                        dot(x + col, y + row, 0xFFFFFFFF);
                    }
                }
        }
        // A faint edge, so the white parts don't melt into a light background.
        Theme.outline(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0x60000000);
    }

    private static void dot(int x, int y, int color) {
        Theme.fill(x, y, x + 1, y + 1, color);
    }
}
