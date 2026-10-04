package WayFarMap.client.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.IconButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;
import WayFarMap.client.map.FlatExport;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;
import WayFarMap.client.map.export.MapExport;
import WayFarMap.client.map.export.MapPictures;
import WayFarMap.client.map.export.TilePyramid;
import WayFarMap.client.map.iso.IsoExport;

/**
 * Pictures of the map, on one screen: on the left a new one is made (the 2D or the 3D map, how detailed, at night,
 * as one picture or as a page that zooms in a browser), on the right the ones made so far, small, to scroll through.
 * A click opens one big to look at closer (zoom and move it), flip through them, copy it to the clipboard, open it or
 * delete it. Only the mod's own pictures are shown and deleted: they are in a folder of their own.
 */
public class GuiMapPictures extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_2D = 1, ID_3D = 2, ID_MAKE = 3, ID_FOLDER = 4, ID_DELETE_ALL = 5,
        ID_VIEW_CLOSE = 6, ID_VIEW_COPY = 7, ID_VIEW_OPEN = 8, ID_VIEW_DELETE = 9, ID_VIEW_PREVIOUS = 10,
        ID_VIEW_NEXT = 11;
    /** Width of the left side, height of a choice, room between the small pictures and their least width. */
    private static final int LEFT_WIDTH = 210, ROW = 15, GAP = 6, CARD_MIN = 112;
    /** Height of the bar over a picture opened big. */
    private static final int VIEW_BAR = 26;
    /** Longest side of the small pictures and of the big one, in pixels of the texture. */
    private static final int THUMB_SIZE = 256, VIEW_SIZE = 2048;
    /** How long a delete button waits for the second click that confirms it, and a message shows. */
    private static final long CONFIRM_MS = 4000, TOAST_MS = 2500;
    /** Rough time to draw one 3D tile on one thread, in seconds, for the estimate. */
    private static final double SECONDS_PER_TILE = 0.2;
    private static final int PICTURE_BACKGROUND = 0xFF0C0E11;
    /** The modes' colors, as on the map's mode list. */
    private static final int FLAT_COLOR = 0xFF5BD6E0, ISO_COLOR = 0xFFE8A040;
    private static final int SELECTED_ROW = 0x334C9AFF;

    /** The choices, kept while the game runs: detail (index in the list) of each mode, at night, as a page. */
    private static int flatQuality = 1, isoQuality = 2;
    private static boolean night, site;

    /** Reads pictures in the background, one at a time. */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap pictures");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });

    /** One detail the map can be saved at. */
    private static final class Quality {

        /** 2D: pixels per block; 3D: the level. */
        final int value;
        final String label, size;
        final boolean usable;

        Quality(int value, String label, String size, boolean usable) {
            this.value = value;
            this.label = label;
            this.size = size;
            this.usable = usable;
        }
    }

    /** A picture read in the background and then put into a texture. */
    private static final class Shown {

        volatile BufferedImage read;
        volatile boolean failed;
        DynamicTexture texture;
        int width, height;

        /** Whether it can be drawn; puts what was read into a texture first (render thread). */
        boolean ready() {
            BufferedImage image = read;
            if (texture == null && image != null) {
                width = image.getWidth();
                height = image.getHeight();
                texture = new DynamicTexture(image);
                read = null;
            }
            return texture != null;
        }

        void release() {
            if (texture != null) {
                texture.deleteGlTexture();
                texture = null;
            }
        }
    }

    private final GuiScreen parent;
    /** What the map shows now, for a new picture: the dimension, which 2D map (surface, biomes, a cave layer). */
    private final int dimension;
    private final String flatWhat, name, dimensionName;
    private boolean iso;
    /**
     * Whether the dimension's 3D map has blocks to save; recording them may be off, what was recorded is still
     * there.
     */
    private boolean isoAvailable;
    private final List<Quality> flatChoices = new ArrayList<>();
    /** Counted the first time 3D is picked (it reads the 3D map); null until then. */
    private List<Quality> isoChoices;

    private List<MapPictures.Picture> pictures = new ArrayList<>();
    /** Bytes the pictures folder takes, counted with the list. */
    private long folderBytes;
    private int seenFinished = -1;
    private final Map<File, Shown> thumbs = new HashMap<>();
    private final Map<File, int[]> dimensions = new HashMap<>();
    private double scroll;

    /** The picture opened big, -1 for none; its texture, zoom (1 = fitted) and how far it is moved. */
    private int viewing = -1;
    private Shown viewImage;
    private File viewFile;
    private double viewZoom = 1, viewPanX, viewPanY;
    private boolean viewDragging;
    private int dragX, dragY;

    /** Button waiting for its second click, and since when. */
    private int armed = -1;
    private long armedAt;
    /** A short message at the bottom, its color and when it was given (set from other threads too). */
    private volatile String toast;
    private volatile int toastColor;
    private volatile long toastAt;

    private int left, top, right, bottom, contentTop, contentBottom, galleryLeft, gridTop;
    private FlatButton flatButton, isoButton, makeButton, folderButton, deleteAllButton;
    private FlatButton copyButton, openButton, deleteButton, previousButton, nextButton;
    private IconButton viewCloseButton;

    /**
     * @param dimension     the dimension shown on the map
     * @param iso           the map shows 3D: it is picked at first
     * @param flatWhat      which 2D map is shown, for the name of the picture ("2d", "biomes", "caves_0-15")
     * @param name          the world and dimension, for the name of the picture
     * @param dimensionName the dimension's name, for the picture's page
     */
    public GuiMapPictures(GuiScreen parent, int dimension, boolean iso, String flatWhat, String name,
        String dimensionName) {
        this.parent = parent;
        this.dimension = dimension;
        this.iso = iso;
        this.flatWhat = flatWhat;
        this.name = name;
        this.dimensionName = dimensionName;
        IsoExport probe = IsoExport.of(dimension, Config.isoRotation, IsoExport.MAX_LEVEL, false);
        this.isoAvailable = probe != null && probe.hasBlocks();
        this.iso = iso && isoAvailable;
        countFlat();
    }

    // ---------------------------------------------------------------- choices

    /** The 2D map's details: 1 to 16 pixels per block, with the picture's size. */
    private void countFlat() {
        MapDimension map = MapManager.INSTANCE.getViewMap();
        Set<Long> regions = map == null ? Collections.<Long>emptySet() : new FlatExport(map, 1).tiles();
        long[] picture = TilePyramid.pictureSize(regions, MapRegion.SIZE);
        for (int pixels = 1; pixels <= 16; pixels *= 2) {
            flatChoices.add(
                new Quality(
                    pixels,
                    I18n.format("wayfarmap.pictures.pixels", pixels),
                    picture[0] * pixels + "×" + picture[1] * pixels,
                    !regions.isEmpty()));
        }
    }

    /** The 3D map's details, from the least: pixels per block, the picture's size and about how long it takes. */
    private void countIso() {
        isoChoices = new ArrayList<>();
        for (int level = IsoExport.MAX_LEVEL; level >= 0; level--) {
            IsoExport export = IsoExport.of(dimension, Config.isoRotation, level, false);
            if (export == null) {
                continue;
            }
            Set<Long> tiles = export.tiles();
            double minutes = tiles.size() * SECONDS_PER_TILE / export.threads() / 60;
            String time = minutes < 1 ? I18n.format("wayfarmap.pictures.under_minute")
                : I18n.format("wayfarmap.pictures.minutes", (int) Math.ceil(minutes));
            long[] picture = TilePyramid.pictureSize(tiles, export.tileSize());
            isoChoices.add(
                new Quality(
                    level,
                    I18n.format("wayfarmap.pictures.pixels", (int) export.pixelsPerBlock()),
                    picture[0] + "×" + picture[1] + " · " + time,
                    !tiles.isEmpty()));
        }
        isoQuality = Math.min(isoQuality, Math.max(0, isoChoices.size() - 1));
        boolean any = false;
        for (Quality quality : isoChoices) {
            any |= quality.usable;
        }
        if (!any) {
            // Files, but no blocks in them: as empty as no 3D map.
            isoAvailable = false;
            iso = false;
        }
    }

    private List<Quality> choices() {
        if (iso && isoChoices == null) {
            countIso();
        }
        return iso ? isoChoices : flatChoices;
    }

    private Quality chosen() {
        List<Quality> list = choices();
        if (list.isEmpty()) {
            return null;
        }
        int index = iso ? isoQuality : flatQuality;
        return list.get(Math.max(0, Math.min(list.size() - 1, index)));
    }

    private void make() {
        Quality quality = chosen();
        if (quality == null || !quality.usable) {
            return;
        }
        TilePyramid.Info info = new TilePyramid.Info();
        if (iso) {
            IsoExport export = IsoExport.of(dimension, Config.isoRotation, quality.value, night);
            if (export == null) {
                return;
            }
            String what = "3d_" + (int) export.pixelsPerBlock() + "px" + (night ? "_night" : "");
            info.title = dimensionName + " (3D)";
            info.mode = "3d";
            info.pixelsPerBlock = export.pixelsPerBlock();
            // Up to 128 screen pixels per block, and always a few times closer than the picture itself.
            info.maxZoom = Math.max(4, 128 / export.pixelsPerBlock());
            MapExport.start(export, info, name + "_" + what, site);
        } else {
            MapDimension map = MapManager.INSTANCE.getViewMap();
            if (map == null) {
                return;
            }
            String what = flatWhat + "_" + quality.value + "px";
            info.title = dimensionName + " (" + what + ")";
            info.mode = "2d";
            info.pixelsPerBlock = quality.value;
            // Up to 64 screen pixels per block, like the closest zoom of the map.
            info.maxZoom = Math.max(4, 64.0 / quality.value);
            MapExport.start(new FlatExport(map, quality.value), info, name + "_" + what, site);
        }
    }

    // ---------------------------------------------------------------- layout

    private int qualityTop() {
        return contentTop + 48;
    }

    private int nightY() {
        return qualityTop() + choices().size() * ROW + 4;
    }

    private int formatLabelY() {
        return (iso ? nightY() + ROW : qualityTop() + choices().size() * ROW) + 8;
    }

    private int formatTop() {
        return formatLabelY() + 12;
    }

    private int columns() {
        return Math.max(1, (right - 10 - galleryLeft + GAP) / (CARD_MIN + GAP));
    }

    private int cardWidth() {
        int columns = columns();
        return (right - 10 - galleryLeft - (columns - 1) * GAP) / columns;
    }

    private int thumbHeight() {
        return cardWidth() * 5 / 8;
    }

    private int cardHeight() {
        return thumbHeight() + 26;
    }

    private int maxScroll() {
        int rows = (pictures.size() + columns() - 1) / columns();
        int total = rows * (cardHeight() + GAP) - GAP;
        return Math.max(0, total - (contentBottom - gridTop));
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 540);
        int panelHeight = Math.min(height - 16, 340);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentTop = top + WindowHeader.HEIGHT + 8;
        contentBottom = bottom - 10;
        galleryLeft = left + 10 + LEFT_WIDTH + 17;
        gridTop = contentTop + 18;

        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, right, top));
        int x = left + 10, half = (LEFT_WIDTH - 4) / 2;
        flatButton = new FlatButton(ID_2D, x, contentTop + 12, half, 16, I18n.format("wayfarmap.pictures.flat"));
        flatButton.icon = Icons.MAP2D;
        flatButton.iconColor = FLAT_COLOR;
        isoButton = new FlatButton(ID_3D, x + LEFT_WIDTH - half, contentTop + 12, half, 16, "3D");
        isoButton.icon = Icons.ISO;
        isoButton.iconColor = ISO_COLOR;
        makeButton = new FlatButton(ID_MAKE, x, contentBottom - 18, LEFT_WIDTH, 18, "");
        buttonList.add(flatButton);
        buttonList.add(isoButton);
        buttonList.add(makeButton);

        String deleteAll = I18n.format("wayfarmap.pictures.delete_all");
        int deleteWidth = Math.max(
            fontRendererObj.getStringWidth(deleteAll),
            fontRendererObj.getStringWidth(I18n.format("wayfarmap.pictures.sure"))) + 12;
        deleteAllButton = new FlatButton(ID_DELETE_ALL, right - 10 - deleteWidth, contentTop - 3, deleteWidth, 14, "");
        deleteAllButton.danger = true;
        String folder = I18n.format("wayfarmap.pictures.folder");
        int folderWidth = fontRendererObj.getStringWidth(folder) + 12;
        folderButton = new FlatButton(
            ID_FOLDER,
            deleteAllButton.xPosition - 4 - folderWidth,
            contentTop - 3,
            folderWidth,
            14,
            folder);
        buttonList.add(folderButton);
        buttonList.add(deleteAllButton);

        // Over a picture opened big: its bar at the top, the arrows on the sides.
        viewCloseButton = new IconButton(ID_VIEW_CLOSE, width - 24, 5, Icons.CLOSE, "");
        deleteButton = viewButton(ID_VIEW_DELETE, "wayfarmap.pictures.delete", "wayfarmap.pictures.sure", width - 28);
        deleteButton.danger = true;
        openButton = viewButton(ID_VIEW_OPEN, "wayfarmap.pictures.open", null, deleteButton.xPosition - 4);
        copyButton = viewButton(ID_VIEW_COPY, "wayfarmap.pictures.copy", null, openButton.xPosition - 4);
        previousButton = new FlatButton(ID_VIEW_PREVIOUS, 6, height / 2 - 20, 18, 40, "<");
        nextButton = new FlatButton(ID_VIEW_NEXT, width - 24, height / 2 - 20, 18, 40, ">");
        buttonList.add(viewCloseButton);
        buttonList.add(copyButton);
        buttonList.add(openButton);
        buttonList.add(deleteButton);
        buttonList.add(previousButton);
        buttonList.add(nextButton);
        refresh();
    }

    /**
     * A button of the bar over a picture opened big, ending at {@code rightX}.
     *
     * @param otherKey the text it shows at times (asking again), so it is wide enough for both; or null
     */
    private FlatButton viewButton(int id, String key, String otherKey, int rightX) {
        String text = I18n.format(key);
        int textWidth = fontRendererObj.getStringWidth(text);
        if (otherKey != null) {
            textWidth = Math.max(textWidth, fontRendererObj.getStringWidth(I18n.format(otherKey)));
        }
        int buttonWidth = textWidth + 14;
        return new FlatButton(id, rightX - buttonWidth, 5, buttonWidth, 16, text);
    }

    // ---------------------------------------------------------------- pictures

    /** Lists the pictures again, letting go of the small pictures of the ones gone. */
    private void refresh() {
        seenFinished = MapExport.finished();
        pictures = MapPictures.list();
        folderBytes = MapPictures.folderSize();
        Set<File> present = new HashSet<>();
        for (MapPictures.Picture picture : pictures) {
            present.add(picture.preview);
        }
        for (Iterator<Map.Entry<File, Shown>> it = thumbs.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<File, Shown> entry = it.next();
            if (!present.contains(entry.getKey())) {
                entry.getValue()
                    .release();
                it.remove();
            }
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        if (viewing >= pictures.size()) {
            viewing = pictures.size() - 1;
        }
        if (viewing >= 0) {
            showBig(viewing);
        }
    }

    private static Shown load(File file, int maxSide) {
        Shown shown = new Shown();
        LOADER.submit(() -> {
            BufferedImage image = MapPictures.read(file, maxSide);
            if (image == null) {
                shown.failed = true;
            } else {
                shown.read = image;
            }
        });
        return shown;
    }

    private Shown thumb(MapPictures.Picture picture) {
        return thumbs.computeIfAbsent(picture.preview, file -> load(file, THUMB_SIZE));
    }

    private int[] dimensions(MapPictures.Picture picture) {
        return dimensions.computeIfAbsent(picture.image, MapPictures::dimensions);
    }

    /** Opens the picture big (or goes on to another one), fitted to the window. */
    private void showBig(int index) {
        if (index < 0 || index >= pictures.size()) {
            closeBig();
            return;
        }
        File file = pictures.get(index).image;
        if (index != viewing || viewImage == null || !file.equals(viewFile)) {
            if (viewImage != null) {
                viewImage.release();
            }
            viewImage = load(file, VIEW_SIZE);
            viewFile = file;
            viewZoom = 1;
            viewPanX = viewPanY = 0;
        }
        viewing = index;
        armed = -1;
    }

    private void closeBig() {
        if (viewImage != null) {
            viewImage.release();
        }
        viewImage = null;
        viewFile = null;
        viewing = -1;
        viewDragging = false;
        armed = -1;
    }

    private void flip(int by) {
        if (viewing >= 0 && !pictures.isEmpty()) {
            showBig(Math.floorMod(viewing + by, pictures.size()));
        }
    }

    private void say(String text, int color) {
        toast = text;
        toastColor = color;
        toastAt = System.currentTimeMillis();
    }

    private void copy(MapPictures.Picture picture) {
        int[] size = dimensions(picture);
        boolean smaller = size != null && Math.max(size[0], size[1]) > MapPictures.CLIPBOARD_MAX;
        say(I18n.format("wayfarmap.pictures.copying"), Theme.TEXT_MUTED);
        MapPictures.copy(picture, failure -> {
            if (failure != null) {
                say(I18n.format("wayfarmap.pictures.copy_failed", failure), Theme.DANGER);
            } else if (smaller) {
                say(I18n.format("wayfarmap.pictures.copied_smaller", MapPictures.CLIPBOARD_MAX), Theme.SUCCESS);
            } else {
                say(I18n.format("wayfarmap.pictures.copied"), Theme.SUCCESS);
            }
        });
    }

    // ---------------------------------------------------------------- input

    /** First click of a delete button: it asks again; true when this click confirms. */
    private boolean confirmed(int id) {
        long now = System.currentTimeMillis();
        if (armed != id || now - armedAt > CONFIRM_MS) {
            armed = id;
            armedAt = now;
            return false;
        }
        armed = -1;
        return true;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_CLOSE:
                mc.displayGuiScreen(parent);
                break;
            case ID_2D:
            case ID_3D:
                iso = button.id == ID_3D;
                break;
            case ID_MAKE:
                if (MapExport.running()) {
                    MapExport.cancel();
                } else {
                    make();
                }
                break;
            case ID_FOLDER:
                File folder = MapExport.folder();
                folder.mkdirs();
                MapPictures.open(folder);
                break;
            case ID_DELETE_ALL:
                if (confirmed(ID_DELETE_ALL)) {
                    int deleted = MapPictures.deleteAll();
                    say(I18n.format("wayfarmap.pictures.deleted_all", deleted), Theme.SUCCESS);
                    refresh();
                }
                break;
            case ID_VIEW_CLOSE:
                closeBig();
                break;
            case ID_VIEW_PREVIOUS:
                flip(-1);
                break;
            case ID_VIEW_NEXT:
                flip(1);
                break;
            case ID_VIEW_COPY:
                copy(pictures.get(viewing));
                break;
            case ID_VIEW_OPEN:
                MapPictures.open(
                    pictures.get(viewing)
                        .openable());
                break;
            case ID_VIEW_DELETE:
                if (confirmed(ID_VIEW_DELETE)) {
                    deleteViewed();
                }
                break;
            default:
                break;
        }
    }

    private void deleteViewed() {
        MapPictures.Picture picture = pictures.get(viewing);
        if (MapPictures.delete(picture)) {
            say(I18n.format("wayfarmap.pictures.deleted"), Theme.SUCCESS);
        } else {
            say(I18n.format("wayfarmap.pictures.delete_failed"), Theme.DANGER);
        }
        int index = viewing;
        refresh();
        if (pictures.isEmpty()) {
            closeBig();
        } else {
            showBig(Math.min(index, pictures.size() - 1));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (viewing >= 0) {
            boolean control = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            if (keyCode == Keyboard.KEY_ESCAPE) {
                if (armed != -1) {
                    armed = -1;
                } else {
                    closeBig();
                }
            } else if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_A) {
                flip(-1);
            } else if (keyCode == Keyboard.KEY_RIGHT || keyCode == Keyboard.KEY_D) {
                flip(1);
            } else if (keyCode == Keyboard.KEY_C && control) {
                copy(pictures.get(viewing));
            } else if (keyCode == Keyboard.KEY_DELETE && confirmed(ID_VIEW_DELETE)) {
                deleteViewed();
            } else if (keyCode == Keyboard.KEY_0 || keyCode == Keyboard.KEY_NUMPAD0) {
                viewZoom = 1;
                viewPanX = viewPanY = 0;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (armed != -1) {
                armed = -1;
            } else {
                mc.displayGuiScreen(parent);
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (viewing >= 0) {
            // Zooms around the mouse: the point under it stays there.
            double before = viewZoom;
            viewZoom = Math.max(1, Math.min(32, viewZoom * (wheel > 0 ? 1.25 : 0.8)));
            double cx = width / 2.0 + viewPanX, cy = (VIEW_BAR + height) / 2.0 + viewPanY;
            double change = viewZoom / before;
            viewPanX += (mouseX - cx) * (1 - change);
            viewPanY += (mouseY - cy) * (1 - change);
            if (viewZoom == 1) {
                viewPanX = viewPanY = 0;
            }
            return;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(wheel) * (cardHeight() + GAP) / 2.0));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (viewing >= 0) {
            if (button == 0 && mouseY > VIEW_BAR && !overButton(mouseX, mouseY)) {
                viewDragging = true;
                dragX = mouseX;
                dragY = mouseY;
            }
            return;
        }
        if (button != 0 || overButton(mouseX, mouseY)) {
            return;
        }
        int x = left + 10;
        if (mouseX >= x && mouseX < x + LEFT_WIDTH) {
            clickChoices(mouseY);
            return;
        }
        int index = cardAt(mouseX, mouseY);
        if (index >= 0) {
            showBig(index);
        }
    }

    private boolean overButton(int mouseX, int mouseY) {
        for (Object o : buttonList) {
            FlatButton b = (FlatButton) o;
            if (b.visible && b.enabled && b.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /** A click on the left side: a detail, night or the format. */
    private void clickChoices(int mouseY) {
        if (MapExport.running()) {
            return;
        }
        List<Quality> list = choices();
        int row = Math.floorDiv(mouseY - qualityTop(), ROW);
        if (row >= 0 && row < list.size()) {
            if (list.get(row).usable) {
                if (iso) {
                    isoQuality = row;
                } else {
                    flatQuality = row;
                }
            }
            return;
        }
        if (iso && mouseY >= nightY() && mouseY < nightY() + ROW) {
            night = !night;
            return;
        }
        int format = Math.floorDiv(mouseY - formatTop(), ROW);
        if (format == 0 || format == 1) {
            site = format == 1;
        }
    }

    /** The small picture under the mouse, or -1. */
    private int cardAt(int mouseX, int mouseY) {
        if (mouseX < galleryLeft || mouseX >= right - 10 || mouseY < gridTop || mouseY >= contentBottom) {
            return -1;
        }
        int columns = columns(), cardWidth = cardWidth(), cardHeight = cardHeight();
        int column = (mouseX - galleryLeft) / (cardWidth + GAP);
        int y = mouseY - gridTop + (int) Math.round(scroll);
        int row = y / (cardHeight + GAP);
        if (column >= columns || (mouseX - galleryLeft) % (cardWidth + GAP) >= cardWidth
            || y % (cardHeight + GAP) >= cardHeight) {
            return -1;
        }
        int index = row * columns + column;
        return index < pictures.size() ? index : -1;
    }

    @Override
    public void onGuiClosed() {
        for (Shown shown : thumbs.values()) {
            shown.release();
        }
        thumbs.clear();
        closeBig();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        if (MapExport.finished() != seenFinished) {
            refresh();
        }
        if (armed != -1 && System.currentTimeMillis() - armedAt > CONFIRM_MS) {
            armed = -1;
        }
        boolean big = viewing >= 0 && viewing < pictures.size();
        updateButtons(big);

        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            right,
            right - WindowHeader.CLOSE_ROOM,
            Icons.CAMERA,
            I18n.format("wayfarmap.pictures.title"),
            I18n.format("wayfarmap.pictures.subtitle"),
            Theme.TEXT_MUTED,
            null);
        drawChoices(big ? -10000 : mouseX, mouseY);
        // Between the two sides.
        Theme.fill(galleryLeft - 9, contentTop, galleryLeft - 8, contentBottom, Theme.BORDER);
        drawGallery(big ? -10000 : mouseX, mouseY);
        if (!big) {
            super.drawScaled(mouseX, mouseY, partialTicks);
            drawToast(height - 4);
            drawIsoNote(mouseX, mouseY);
            return;
        }
        drawBig(mouseX, mouseY);
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawToast(height - 4);
    }

    /** Why 3D can't be picked, under the mouse over its button. */
    private void drawIsoNote(int mouseX, int mouseY) {
        if (isoAvailable || !isoButton.isMouseOver(mouseX, mouseY)) {
            return;
        }
        drawHoveringText(
            fontRendererObj.listFormattedStringToWidth(I18n.format("wayfarmap.pictures.iso_empty"), 180),
            mouseX,
            mouseY,
            fontRendererObj);
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    private void updateButtons(boolean big) {
        boolean running = MapExport.running();
        Quality quality = chosen();
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            boolean viewButton = b.id >= ID_VIEW_CLOSE;
            b.visible = viewButton == big;
        }
        flatButton.active = !iso;
        isoButton.active = iso;
        flatButton.enabled = !running;
        isoButton.enabled = !running && isoAvailable;
        makeButton.displayString = I18n.format(running ? "wayfarmap.pictures.stop" : "wayfarmap.pictures.make");
        makeButton.danger = running;
        makeButton.active = !running && quality != null && quality.usable;
        makeButton.enabled = running || quality != null && quality.usable;
        makeButton.icon = running ? null : Icons.CAMERA;
        deleteAllButton.displayString = I18n.format(
            armed == ID_DELETE_ALL ? "wayfarmap.pictures.sure" : "wayfarmap.pictures.delete_all");
        deleteAllButton.active = armed == ID_DELETE_ALL;
        deleteAllButton.enabled = !pictures.isEmpty() && !running;
        deleteButton.displayString = I18n.format(
            armed == ID_VIEW_DELETE ? "wayfarmap.pictures.sure" : "wayfarmap.pictures.delete");
        deleteButton.active = armed == ID_VIEW_DELETE;
        previousButton.enabled = nextButton.enabled = pictures.size() > 1;
    }

    private void drawChoices(int mouseX, int mouseY) {
        int x = left + 10, x1 = x + LEFT_WIDTH;
        boolean running = MapExport.running();
        Theme.text(fontRendererObj, I18n.format("wayfarmap.pictures.new"), x, contentTop, Theme.TEXT);

        // How detailed, with the picture's size.
        Theme.text(fontRendererObj, I18n.format("wayfarmap.pictures.quality"), x, contentTop + 36, Theme.TEXT_MUTED);
        List<Quality> list = choices();
        int selected = iso ? isoQuality : flatQuality;
        if (list.isEmpty()) {
            String none = I18n.format("wayfarmap.pictures.no_3d");
            Theme.text(fontRendererObj, none, x, qualityTop() + 3, Theme.TEXT_DISABLED);
        }
        for (int i = 0; i < list.size(); i++) {
            Quality quality = list.get(i);
            int y = qualityTop() + i * ROW;
            boolean hovered = !running && quality.usable && Theme.inside(mouseX, mouseY, x, y, x1, y + ROW);
            drawChoiceRow(x, y, x1, i == selected, hovered, quality.usable && !running, true);
            int labelColor = quality.usable ? Theme.TEXT : Theme.TEXT_DISABLED;
            Theme.text(fontRendererObj, quality.label, x + 14, y + 4, labelColor);
            int labelWidth = fontRendererObj.getStringWidth(quality.label);
            String size = Theme.ellipsize(fontRendererObj, quality.size, x1 - 4 - (x + 14 + labelWidth + 6));
            Theme.text(
                fontRendererObj,
                size,
                x1 - 4 - fontRendererObj.getStringWidth(size),
                y + 4,
                i == selected ? Theme.ACCENT : Theme.TEXT_MUTED);
        }
        if (iso) {
            int y = nightY();
            boolean hovered = !running && Theme.inside(mouseX, mouseY, x, y, x1, y + ROW);
            drawChoiceRow(x, y, x1, night, hovered, !running, false);
            Theme.text(fontRendererObj, I18n.format("wayfarmap.pictures.night"), x + 14, y + 4, Theme.TEXT);
            Icons.draw(Icons.NIGHT, x1 - 4 - Icons.width(Icons.NIGHT), y + 3, night ? 0xFFB9A8FF : Theme.TEXT_DISABLED);
        }

        // One picture, or a page for the browser.
        int formatY = formatLabelY();
        Theme.text(fontRendererObj, I18n.format("wayfarmap.pictures.format"), x, formatY, Theme.TEXT_MUTED);
        String[] formats = { "single", "site" };
        for (int i = 0; i < formats.length; i++) {
            int y = formatTop() + i * ROW;
            boolean on = site == (i == 1);
            boolean hovered = !running && Theme.inside(mouseX, mouseY, x, y, x1, y + ROW);
            drawChoiceRow(x, y, x1, on, hovered, !running, true);
            Theme.text(fontRendererObj, I18n.format("wayfarmap.pictures." + formats[i]), x + 14, y + 4, Theme.TEXT);
            String tag = I18n.format("wayfarmap.pictures." + formats[i] + "_tag");
            Theme.text(
                fontRendererObj,
                tag,
                x1 - 4 - fontRendererObj.getStringWidth(tag),
                y + 4,
                on ? Theme.ACCENT : Theme.TEXT_MUTED);
        }
        int hintY = formatTop() + 2 * ROW + 4;
        String hintText = I18n.format("wayfarmap.pictures." + (site ? "site" : "single") + "_hint");
        List<?> hint = fontRendererObj.listFormattedStringToWidth(hintText, LEFT_WIDTH);
        for (int i = 0; i < hint.size() && hintY + i * 10 + 8 < makeButton.yPosition - 18; i++) {
            Theme.text(fontRendererObj, (String) hint.get(i), x, hintY + i * 10, Theme.TEXT_MUTED);
        }

        // Making one: how far it got, over the button that stops it.
        if (running) {
            int barY = makeButton.yPosition - 15;
            double progress = MapExport.progress();
            Theme.fill(x, barY, x1, barY + 10, Theme.CONTROL);
            if (progress >= 0) {
                Theme.fill(x, barY, x + (int) Math.round(LEFT_WIDTH * progress), barY + 10, Theme.ACCENT_DIM);
            } else {
                // Preparing: a stripe going back and forth.
                double t = System.currentTimeMillis() % 1600 / 1600.0;
                int stripe = LEFT_WIDTH / 4;
                int from = x + (int) Math.round((LEFT_WIDTH - stripe) * (0.5 - 0.5 * Math.cos(t * 2 * Math.PI)));
                Theme.fill(from, barY, from + stripe, barY + 10, Theme.ACCENT_DIM);
            }
            Theme.outline(x, barY, x1, barY + 10, Theme.BORDER);
            String status = MapExport.statusText();
            if (status != null) {
                Theme.centered(
                    fontRendererObj,
                    Theme.ellipsize(fontRendererObj, status, LEFT_WIDTH - 4),
                    (x + x1) / 2,
                    barY + 1,
                    Theme.TEXT);
            }
        }
    }

    /** A choice's row: lit under the mouse, marked when chosen; a round mark for one of several, else a box. */
    private void drawChoiceRow(int x, int y, int x1, boolean on, boolean hovered, boolean enabled, boolean round) {
        if (on) {
            Theme.fill(x, y, x1, y + ROW - 1, SELECTED_ROW);
            Theme.fill(x, y, x + 2, y + ROW - 1, Theme.ACCENT);
        } else if (hovered) {
            Theme.fill(x, y, x1, y + ROW - 1, Theme.ROW_HOVER);
        }
        double cx = x + 8, cy = y + 7;
        int ring = enabled ? Theme.BORDER : Theme.CONTROL;
        if (round) {
            Theme.disc(cx, cy, 3.5, on ? Theme.ACCENT : ring);
            Theme.disc(cx, cy, 2.5, Theme.PANEL | 0xFF000000);
            if (on) {
                Theme.disc(cx, cy, 1.5, Theme.ACCENT);
            }
        } else {
            Theme.fill(x + 5, y + 4, x + 12, y + 11, on ? Theme.ACCENT : ring);
            if (!on) {
                Theme.fill(x + 6, y + 5, x + 11, y + 10, Theme.PANEL | 0xFF000000);
            }
        }
    }

    private void drawGallery(int mouseX, int mouseY) {
        int x1 = right - 10;
        String title = I18n.format("wayfarmap.pictures.gallery");
        Theme.text(fontRendererObj, title, galleryLeft, contentTop, Theme.TEXT);
        if (!pictures.isEmpty()) {
            String count = String.valueOf(pictures.size());
            int pillX = galleryLeft + fontRendererObj.getStringWidth(title) + 5;
            int pillRight = pillX + fontRendererObj.getStringWidth(count) + 6;
            Theme.fill(pillX, contentTop - 2, pillRight, contentTop + 9, Theme.CONTROL);
            Theme.text(fontRendererObj, count, pillX + 3, contentTop, Theme.TEXT_MUTED);
        }
        if (folderBytes > 0) {
            // How much the whole folder takes on disk, up to the buttons.
            int sizeX = galleryLeft + fontRendererObj.getStringWidth(title) + 5;
            if (!pictures.isEmpty()) {
                sizeX += fontRendererObj.getStringWidth(String.valueOf(pictures.size())) + 11;
            }
            String size = Theme.ellipsize(
                fontRendererObj,
                I18n.format("wayfarmap.pictures.folder_size", bytes(folderBytes)),
                folderButton.xPosition - 6 - sizeX);
            Theme.text(fontRendererObj, size, sizeX, contentTop, Theme.TEXT_MUTED);
        }
        if (pictures.isEmpty()) {
            int cx = (galleryLeft + x1) / 2, cy = (gridTop + contentBottom) / 2;
            Icons.draw(Icons.CAMERA, cx - Icons.width(Icons.CAMERA) / 2, cy - 22, Theme.TEXT_DISABLED);
            Theme.centered(fontRendererObj, I18n.format("wayfarmap.pictures.empty"), cx, cy - 6, Theme.TEXT_MUTED);
            String hint = I18n.format("wayfarmap.pictures.empty_hint");
            Theme.centered(fontRendererObj, hint, cx, cy + 6, Theme.TEXT_DISABLED);
            return;
        }
        int columns = columns(), cardWidth = cardWidth(), cardHeight = cardHeight(), thumbHeight = thumbHeight();
        int hovered = cardAt(mouseX, mouseY);
        SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        Theme.clip(galleryLeft, gridTop, x1, contentBottom);
        for (int i = 0; i < pictures.size(); i++) {
            int cx = galleryLeft + i % columns * (cardWidth + GAP);
            int cy = gridTop + i / columns * (cardHeight + GAP) - (int) Math.round(scroll);
            if (cy + cardHeight <= gridTop || cy >= contentBottom) {
                continue;
            }
            MapPictures.Picture picture = pictures.get(i);
            boolean lit = i == hovered;
            Theme.fill(cx, cy, cx + cardWidth, cy + cardHeight, lit ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(cx, cy, cx + cardWidth, cy + cardHeight, lit ? Theme.ACCENT : Theme.BORDER);
            drawFitted(thumb(picture), cx + 1, cy + 1, cx + cardWidth - 1, cy + 1 + thumbHeight);
            if (picture.site) {
                // A page for the browser, not only a picture.
                String web = I18n.format("wayfarmap.pictures.web");
                int webWidth = fontRendererObj.getStringWidth(web) + 6;
                Theme.fill(cx + 4, cy + 4, cx + 4 + webWidth, cy + 15, 0xE0161A20);
                Theme.text(fontRendererObj, web, cx + 7, cy + 6, Theme.ACCENT);
            }
            int textX = cx + 4, textWidth = cardWidth - 8;
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, picture.name, textWidth),
                textX,
                cy + thumbHeight + 4,
                Theme.TEXT);
            String info = date.format(new Date(picture.modified)) + " · " + bytes(picture.bytes);
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, info, textWidth),
                textX,
                cy + thumbHeight + 14,
                Theme.TEXT_MUTED);
        }
        Theme.unclip();
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int visible = contentBottom - gridTop;
            Theme.scrollbar(x1 + 4, gridTop, contentBottom, visible, visible + maxScroll, scroll / maxScroll, false);
        }
    }

    /** The picture fitted in the box on a dark ground, centered; loading or unreadable shows a note instead. */
    private void drawFitted(Shown shown, int x0, int y0, int x1, int y1) {
        Theme.fill(x0, y0, x1, y1, PICTURE_BACKGROUND);
        if (!shown.ready()) {
            String note = I18n.format(shown.failed ? "wayfarmap.pictures.unreadable" : "wayfarmap.pictures.loading");
            Theme.centered(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, note, x1 - x0 - 4),
                (x0 + x1) / 2,
                (y0 + y1) / 2 - 4,
                Theme.TEXT_DISABLED);
            return;
        }
        double fit = Math.min((x1 - x0) / (double) shown.width, (y1 - y0) / (double) shown.height);
        double w = shown.width * fit, h = shown.height * fit;
        double px = (x0 + x1 - w) / 2, py = (y0 + y1 - h) / 2;
        drawTexture(shown, px, py, px + w, py + h);
    }

    private static void drawTexture(Shown shown, double x0, double y0, double x1, double y1) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shown.texture.getGlTextureId());
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x0, y1, 0, 0, 1);
        tessellator.addVertexWithUV(x1, y1, 0, 1, 1);
        tessellator.addVertexWithUV(x1, y0, 0, 1, 0);
        tessellator.addVertexWithUV(x0, y0, 0, 0, 0);
        tessellator.draw();
    }

    /** A picture opened big over the whole window, with its bar at the top. */
    private void drawBig(int mouseX, int mouseY) {
        if (viewDragging) {
            if (!Mouse.isButtonDown(0)) {
                viewDragging = false;
            } else {
                viewPanX += mouseX - dragX;
                viewPanY += mouseY - dragY;
                dragX = mouseX;
                dragY = mouseY;
            }
        }
        MapPictures.Picture picture = pictures.get(viewing);
        Theme.fill(0, 0, width, height, 0xF4080A0D);
        int areaTop = VIEW_BAR, areaBottom = height - 14;
        if (viewImage != null && viewImage.ready()) {
            double fit = Math.min(
                (width - 60) / (double) viewImage.width,
                (areaBottom - areaTop - 8) / (double) viewImage.height);
            double w = viewImage.width * fit * viewZoom, h = viewImage.height * fit * viewZoom;
            double cx = width / 2.0 + viewPanX, cy = (areaTop + areaBottom) / 2.0 + viewPanY;
            Theme.clip(0, areaTop, width, areaBottom);
            Theme.fill(
                (int) Math.floor(cx - w / 2),
                (int) Math.floor(cy - h / 2),
                (int) Math.ceil(cx + w / 2),
                (int) Math.ceil(cy + h / 2),
                PICTURE_BACKGROUND);
            drawTexture(viewImage, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            Theme.unclip();
        } else {
            boolean failed = viewImage != null && viewImage.failed;
            Theme.centered(
                fontRendererObj,
                I18n.format(failed ? "wayfarmap.pictures.unreadable" : "wayfarmap.pictures.loading"),
                width / 2,
                (areaTop + areaBottom) / 2 - 4,
                Theme.TEXT_MUTED);
        }

        // The bar: which picture of how many, its name, size in pixels and on disk.
        Theme.fill(0, 0, width, VIEW_BAR, Theme.PANEL_ALT);
        Theme.fill(0, VIEW_BAR - 1, width, VIEW_BAR, Theme.BORDER);
        String counter = (viewing + 1) + " / " + pictures.size();
        int counterWidth = fontRendererObj.getStringWidth(counter) + 8;
        Theme.fill(8, 7, 8 + counterWidth, 19, Theme.CONTROL);
        Theme.text(fontRendererObj, counter, 12, 9, Theme.TEXT_MUTED);
        int[] size = dimensions(picture);
        String details = (size == null ? "" : size[0] + "×" + size[1] + " · ") + bytes(picture.bytes)
            + (picture.site ? " · " + I18n.format("wayfarmap.pictures.web") : "");
        int textX = 8 + counterWidth + 8, textRoom = copyButton.xPosition - 8 - textX;
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, picture.name, textRoom),
            textX,
            4,
            Theme.TEXT);
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, details, textRoom),
            textX,
            14,
            Theme.TEXT_MUTED);
        if (System.currentTimeMillis() - toastAt > TOAST_MS) {
            String hint = I18n.format("wayfarmap.pictures.view_hint");
            Theme.centered(fontRendererObj, hint, width / 2, height - 11, Theme.TEXT_DISABLED);
        }
    }

    private void drawToast(int bottomY) {
        String text = toast;
        if (text == null || System.currentTimeMillis() - toastAt > TOAST_MS) {
            return;
        }
        int textWidth = fontRendererObj.getStringWidth(text);
        int x0 = (width - textWidth) / 2 - 6, y0 = bottomY - 14;
        Theme.fill(x0, y0, x0 + textWidth + 12, bottomY, 0xF0161A20);
        Theme.outline(x0, y0, x0 + textWidth + 12, bottomY, toastColor);
        Theme.text(fontRendererObj, text, x0 + 6, y0 + 3, toastColor);
    }

    /** Bytes as a short text: KB, MB or GB. */
    private static String bytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
