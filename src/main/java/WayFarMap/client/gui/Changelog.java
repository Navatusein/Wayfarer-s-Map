package WayFarMap.client.gui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import WayFarMap.client.Lang;

/**
 * What changed in each version, read from {@code assets/wayfarmap/changelog/<language>.md} (English when the mod's
 * language has none). A small Markdown:
 *
 * <pre>
 * # 1.2.0 | 2026-10-08     a version, the date after "|" (optional)
 * ## Map                   a heading inside a version
 * - [new] text             a point; the tag before it is optional: new, fix, change, remove
 *   - text                 a point inside the one above
 * &gt; text                   a note
 * text                     a paragraph
 * **bold**, `key`, *italic* inside any text
 * &lt;!-- ... --&gt;             left out, also over several lines
 * </pre>
 */
final class Changelog {

    /** The tag of a point: how it is marked. */
    enum Tag {
        NEW(0xFF3FB950),
        FIX(0xFFE3B341),
        CHANGE(0xFF4C9AFF),
        REMOVE(0xFFE5534B);

        final int color;

        Tag(int color) {
            this.color = color;
        }

        /** The translation key of its badge. */
        String key() {
            return "wayfarmap.changelog.tag." + name().toLowerCase(Locale.ROOT);
        }

        static Tag of(String name) {
            switch (name.toLowerCase(Locale.ROOT)) {
                case "new":
                case "add":
                case "added":
                    return NEW;
                case "fix":
                case "fixed":
                    return FIX;
                case "change":
                case "changed":
                    return CHANGE;
                case "remove":
                case "removed":
                    return REMOVE;
                default:
                    return null;
            }
        }
    }

    /** What a line of a version is. */
    enum Kind {
        HEADING,
        POINT,
        SUB_POINT,
        NOTE,
        TEXT
    }

    /** A line of a version: what it is, its tag (points only, may be null) and its text, formatted with §. */
    static final class Entry {

        final Kind kind;
        final Tag tag;
        final String text;

        Entry(Kind kind, Tag tag, String text) {
            this.kind = kind;
            this.tag = tag;
            this.text = text;
        }
    }

    /** A version: its number, its date (may be empty) and what changed. */
    static final class Release {

        final String version, date;
        final List<Entry> entries = new ArrayList<>();

        Release(String version, String date) {
            this.version = version;
            this.date = date;
        }
    }

    private static final Pattern TAG = Pattern.compile("^\\[([A-Za-z]+)\\]\\s*");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`(.+?)`");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])[*_](?![\\s*_])(.+?)(?<![\\s*_])[*_](?![\\w*])");
    private static final Pattern NUMBERS = Pattern.compile("^\\D*(\\d+(?:\\.\\d+)*)");

    private Changelog() {}

    /** The versions, the newest first as in the file, in the mod's language. */
    static List<Release> load() {
        List<Release> releases = read(Lang.code());
        if (releases.isEmpty() && !"en_US".equals(Lang.code())) {
            releases = read("en_US");
        }
        return releases;
    }

    private static List<Release> read(String language) {
        ResourceLocation location = new ResourceLocation("wayfarmap", "changelog/" + language + ".md");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(location)
                    .getInputStream(),
                StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return parse(lines);
        } catch (Exception e) {
            // No file for the language: the caller falls back to English.
            return Collections.emptyList();
        }
    }

    static List<Release> parse(List<String> lines) {
        List<Release> releases = new ArrayList<>();
        Release current = null;
        boolean comment = false;
        // The line before was a point, a note or a paragraph, which the next plain line carries on.
        boolean continues = false;
        for (String raw : lines) {
            String line = raw.replace("\t", "    ");
            if (comment || line.contains("<!--")) {
                // Comments are dropped, the rest of the line kept.
                StringBuilder kept = new StringBuilder();
                int i = 0;
                while (i < line.length()) {
                    if (comment) {
                        int end = line.indexOf("-->", i);
                        if (end < 0) {
                            i = line.length();
                        } else {
                            comment = false;
                            i = end + 3;
                        }
                    } else {
                        int start = line.indexOf("<!--", i);
                        if (start < 0) {
                            kept.append(line, i, line.length());
                            i = line.length();
                        } else {
                            kept.append(line, i, start);
                            comment = true;
                            i = start + 4;
                        }
                    }
                }
                line = kept.toString();
                if (line.trim()
                    .isEmpty()) {
                    continue;
                }
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continues = false;
                continue;
            }
            if (trimmed.startsWith("# ")) {
                continues = false;
                String title = trimmed.substring(2)
                    .trim();
                String version = title, date = "";
                int bar = title.indexOf('|');
                if (bar >= 0) {
                    version = title.substring(0, bar)
                        .trim();
                    date = title.substring(bar + 1)
                        .trim();
                }
                current = new Release(version, date);
                releases.add(current);
                continue;
            }
            if (current == null) {
                // Before the first version: the file's own notes.
                continue;
            }
            int indent = line.length() - line.replaceAll("^\\s+", "")
                .length();
            if (trimmed.startsWith("#")) {
                continues = false;
                String heading = trimmed.replaceAll("^#+\\s*", "");
                current.entries.add(new Entry(Kind.HEADING, null, inline(heading)));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                String text = trimmed.substring(2)
                    .trim();
                Tag tag = null;
                Matcher matcher = TAG.matcher(text);
                if (matcher.find()) {
                    tag = Tag.of(matcher.group(1));
                    if (tag != null) {
                        text = text.substring(matcher.end());
                    }
                }
                Kind kind = indent >= 2 ? Kind.SUB_POINT : Kind.POINT;
                current.entries.add(new Entry(kind, tag, inline(text)));
            } else if (trimmed.startsWith(">")) {
                current.entries.add(
                    new Entry(
                        Kind.NOTE,
                        null,
                        inline(
                            trimmed.substring(1)
                                .trim())));
            } else if (continues && !current.entries.isEmpty()) {
                // A line right under a point, a note or a paragraph carries it on, as in Markdown.
                Entry last = current.entries.remove(current.entries.size() - 1);
                current.entries.add(new Entry(last.kind, last.tag, last.text + " " + inline(trimmed)));
            } else {
                current.entries.add(new Entry(Kind.TEXT, null, inline(trimmed)));
            }
            continues = true;
        }
        return releases;
    }

    /** Markdown's bold, code and italic as Minecraft's formatting; §r goes back to the line's own color. */
    private static String inline(String text) {
        text = CODE.matcher(text)
            .replaceAll("§e$1§r");
        text = BOLD.matcher(text)
            .replaceAll("§f§l$1§r");
        text = ITALIC.matcher(text)
            .replaceAll("§o$1§r");
        return text;
    }

    /**
     * Compares two versions by their numbers ("0.0.13-5-gabc" is 0.0.13): below 0 if {@code a} is older. A
     * version without numbers is older than any.
     */
    static int compare(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return Integer.compare(x.length == 0 ? 0 : 1, y.length == 0 ? 0 : 1);
    }

    private static int[] numbers(String version) {
        Matcher matcher = NUMBERS.matcher(version == null ? "" : version);
        if (!matcher.find()) {
            return new int[0];
        }
        String[] parts = matcher.group(1)
            .split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = Integer.MAX_VALUE;
            }
        }
        return numbers;
    }
}
