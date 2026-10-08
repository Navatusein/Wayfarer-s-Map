package WayFarMap.client.map.iso;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

import WayFarMap.WayFarMap;

/**
 * The caches of block pictures ({@link FaceRenderer#exportCaches}) kept from game to game: which pictures a block
 * gets where, so a base taken before isn't drawn again after the game is started anew. In
 * {@code wayfarmap/cache/<the world's folder>/}, next to {@code logs} and {@code dumps}: they can be deleted at any
 * time (pictures are then learned again). Only used with the palette they were made with (its generation), the same
 * resource packs and the same mods in the same versions (a mod changed may draw its blocks otherwise).
 */
final class PictureCache {

    private static final int MAGIC = 0x57465043; // "WFPC"
    private static final int VERSION = 1;
    private static final String NAME = "3d-pictures.dat";
    private static long modsHash;

    private PictureCache() {}

    /** Where the caches of a world's 3D map are kept. */
    static File fileFor(File gameDirectory, File worldDirectory) {
        File root = new File(gameDirectory, "wayfarmap");
        String world;
        String rootPath = root.getAbsoluteFile()
            .toPath()
            .normalize()
            .toString();
        String worldPath = worldDirectory.getAbsoluteFile()
            .toPath()
            .normalize()
            .toString();
        if (worldPath.startsWith(rootPath + File.separator)) {
            // The same folders as the maps: wayfarmap/singleplayer/<world>/<player> -> cache/singleplayer/...
            world = worldPath.substring(rootPath.length() + 1);
        } else {
            world = worldDirectory.getName() + "-" + Integer.toHexString(worldPath.hashCode());
        }
        return new File(new File(new File(root, "cache"), world), NAME);
    }

    /** The mods and their versions: blocks may look otherwise with others. */
    private static long modsHash() {
        if (modsHash == 0) {
            long h = 0xCBF29CE484222325L;
            try {
                for (ModContainer mod : Loader.instance()
                    .getActiveModList()) {
                    h = (h ^ (mod.getModId() + "@" + mod.getVersion()).hashCode()) * 0x100000001B3L;
                }
            } catch (RuntimeException e) {
                h = 1;
            }
            modsHash = h == 0 ? 1 : h;
        }
        return modsHash;
    }

    /** Takes in the caches kept for the world, if they belong to its palette (render thread, world joined). */
    static void load(File file, FacePalette palette) {
        if (palette == null || !file.isFile()) {
            IsoLog.log("PICTURE_CACHE none yet at " + file);
            return;
        }
        long start = System.nanoTime();
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 1 << 16)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                IsoLog.log("PICTURE_CACHE ignored: an older kind of file");
                return;
            }
            int generation = in.readInt(), packs = in.readInt();
            long mods = in.readLong();
            if (generation != palette.generation || packs != palette.packs) {
                IsoLog.log("PICTURE_CACHE ignored: made for other pictures (resource packs changed or the 3D map was"
                    + " deleted)");
                return;
            }
            if (mods != modsHash()) {
                IsoLog.log("PICTURE_CACHE ignored: mods or their versions changed, pictures are learned again");
                return;
            }
            FaceRenderer.Imported counts = FaceRenderer.importCaches(in, generation, palette.size());
            IsoLog.log(
                "PICTURE_CACHE loaded entries=" + counts.total()
                    + " [surroundings="
                    + counts.surroundings
                    + " kinds="
                    + counts.kinds
                    + " blocks="
                    + counts.blocks
                    + " data="
                    + counts.data
                    + " wholeCubes="
                    + counts.wholeCubes
                    + " spriteLooks="
                    + counts.looks
                    + " dataClasses="
                    + counts.classes
                    + "] droppedForMissingPictures="
                    + counts.dropped
                    + " fileKB="
                    + file.length() / 1024
                    + " ms="
                    + (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            WayFarMap.LOG.warn("Could not read the 3D map's picture caches " + file, e);
            IsoLog.log("PICTURE_CACHE unreadable (" + e + "): pictures are learned again");
        }
    }

    /**
     * Writes the caches (from {@link FaceRenderer#exportCaches}) after the pictures they name are saved (saver
     * thread). A new file replaces the old one only once written whole.
     */
    static void save(File file, byte[] caches, int generation, int packs) {
        long start = System.nanoTime();
        File directory = file.getParentFile();
        File temporary = new File(directory, NAME + ".tmp");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("can't make " + directory);
            }
            try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temporary), 1 << 16)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(generation);
                out.writeInt(packs);
                out.writeLong(modsHash());
                out.write(caches);
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            IsoLog.log(
                "PICTURE_CACHE saved rawKB=" + caches.length / 1024
                    + " fileKB="
                    + file.length() / 1024
                    + " ms="
                    + (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            WayFarMap.LOG.warn("Could not save the 3D map's picture caches " + file, e);
            IsoLog.log("PICTURE_CACHE not saved: " + e);
        }
    }
}
