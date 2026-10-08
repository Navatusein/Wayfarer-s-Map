package WayFarMap.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import WayFarMap.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The mod's own translations, in the language chosen in its settings ({@link Config#modLanguage}) whatever the game's
 * language is. Read from {@code assets/wayfarmap/lang/<code>.lang} (resource packs can override them), a key missing
 * there from en_US, and a key the mod doesn't have from the game's translations. The game's own keys the mod uses
 * ("gui.done") are looked up first as {@code wayfarmap.vanilla.<key>}, so they follow the mod's language too.
 */
public final class Lang {

    /** The languages to choose from, in the order of {@link Config#modLanguage}. */
    public static final String[] CODES = { "en_US", "ru_RU", "uk_UA", "zh_CN" };
    private static final String FALLBACK = "en_US";
    /** Like the game: %d and %.1f become %s, so numbers given as text still format. */
    private static final Pattern NUMERIC = Pattern.compile("%(\\d+\\$)?[\\d\\.]*[df]");

    private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

    private Lang() {}

    /** The code of the language chosen in the settings, e.g. "ru_RU". */
    public static String code() {
        return CODES[Math.max(0, Math.min(CODES.length - 1, Config.modLanguage))];
    }

    /** The key's text in the mod's language, formatted with the arguments like {@link I18n#format}. */
    public static String format(String key, Object... args) {
        String value = find(key);
        if (value == null) {
            return I18n.format(key, args);
        }
        try {
            return String.format(value, args);
        } catch (IllegalFormatException e) {
            return "Format error: " + value;
        }
    }

    /** Whether the mod, or else the game, has a translation for the key. */
    public static boolean has(String key) {
        return find(key) != null || !I18n.format(key)
            .equals(key);
    }

    /** A chat line of the key's text in the mod's language. */
    public static IChatComponent chat(String key, Object... args) {
        return new ChatComponentText(format(key, args));
    }

    /** Forgets the read files, so they are read again (resource packs changed). */
    public static synchronized void reload() {
        TABLES.clear();
    }

    private static String find(String key) {
        String vanilla = key.startsWith("wayfarmap.") || key.startsWith("key.wayfarmap") ? null
            : "wayfarmap.vanilla." + key;
        String language = code();
        String value = lookup(language, key, vanilla);
        if (value == null && !language.equals(FALLBACK)) {
            value = lookup(FALLBACK, key, vanilla);
        }
        return value;
    }

    private static String lookup(String language, String key, String vanilla) {
        Map<String, String> table = table(language);
        String value = vanilla != null ? table.get(vanilla) : null;
        return value != null ? value : table.get(key);
    }

    private static synchronized Map<String, String> table(String language) {
        Map<String, String> table = TABLES.get(language);
        if (table == null) {
            table = read(language);
            TABLES.put(language, table);
        }
        return table;
    }

    /** Every resource pack's file for the language, later ones over earlier ones. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> read(String language) {
        Map<String, String> table = new HashMap<>();
        ResourceLocation location = new ResourceLocation("wayfarmap", "lang/" + language + ".lang");
        List<IResource> resources;
        try {
            resources = Minecraft.getMinecraft()
                .getResourceManager()
                .getAllResources(location);
        } catch (IOException | RuntimeException e) {
            return table;
        }
        for (IResource resource : resources) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        String value = line.substring(equals + 1);
                        table.put(
                            line.substring(0, equals),
                            NUMERIC.matcher(value)
                                .replaceAll("%$1s"));
                    }
                }
            } catch (IOException e) {
                // Leave out what couldn't be read.
            }
        }
        return table;
    }

    /**
     * The mod's chat lines sent by the server are translated by the game in its own language; this puts them in the
     * mod's.
     */
    public static final class ChatTranslator {

        public static final ChatTranslator INSTANCE = new ChatTranslator();

        private ChatTranslator() {}

        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent event) {
            if (!(event.message instanceof ChatComponentTranslation)) {
                return;
            }
            ChatComponentTranslation message = (ChatComponentTranslation) event.message;
            if (!message.getKey()
                .startsWith("wayfarmap.")) {
                return;
            }
            Object[] args = message.getFormatArgs()
                .clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof IChatComponent) {
                    args[i] = ((IChatComponent) args[i]).getUnformattedText();
                }
            }
            IChatComponent line = chat(message.getKey(), args);
            line.setChatStyle(
                message.getChatStyle()
                    .createShallowCopy());
            for (Object sibling : message.getSiblings()) {
                line.appendSibling((IChatComponent) sibling);
            }
            event.message = line;
        }
    }
}
