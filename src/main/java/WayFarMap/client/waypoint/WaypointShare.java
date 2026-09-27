package WayFarMap.client.waypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Sharing waypoints through the chat. The shared waypoint goes out as a plain chat line
 * ({@code [WFM]|name|x|y|z|dim|color|icon|group|beam}, at most 100 characters, the chat limit), so it works on any
 * server; players with the mod see it as "shared a waypoint <name> (x, y, z) [Add]", and clicking [Add] runs a
 * client command that adds it, with its group (created if missing).
 */
public final class WaypointShare {

    public static final WaypointShare INSTANCE = new WaypointShare();

    private static final String PREFIX = "[WFM]|";
    private static final int CHAT_LIMIT = 100;
    private static final String COMMAND = "wayfarmap_add";

    private WaypointShare() {}

    /** Sends the waypoint to the chat. */
    public static void share(Waypoint waypoint) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage(encode(waypoint));
        }
    }

    /**
     * The chat line for a waypoint, shortened to fit the chat limit: long names and groups are cut first, then the
     * icon goes, then the name gets shorter, and only then the group (so a shared waypoint keeps its group).
     */
    static String encode(Waypoint waypoint) {
        String name = cut(clean(waypoint.name), 32);
        String group = waypoint.group == null ? "" : cut(clean(waypoint.group), 24);
        String icon = waypoint.iconItem == null ? "" : waypoint.iconItem + "@" + waypoint.iconMeta;
        String text = build(name, waypoint, icon, group);
        if (text.length() > CHAT_LIMIT) {
            icon = "";
            text = build(name, waypoint, icon, group);
        }
        while (text.length() > CHAT_LIMIT && name.length() > 8) {
            name = name.substring(0, name.length() - 1);
            text = build(name, waypoint, icon, group);
        }
        while (text.length() > CHAT_LIMIT && !group.isEmpty()) {
            group = group.substring(0, group.length() - 1);
            text = build(name, waypoint, icon, group);
        }
        while (text.length() > CHAT_LIMIT && !name.isEmpty()) {
            name = name.substring(0, name.length() - 1);
            text = build(name, waypoint, icon, group);
        }
        return text;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String build(String name, Waypoint waypoint, String icon, String group) {
        return PREFIX + name
            + "|"
            + waypoint.x
            + "|"
            + waypoint.y
            + "|"
            + waypoint.z
            + "|"
            + waypoint.dimension
            + "|"
            + (waypoint.outlineColor == null ? "" : Integer.toHexString(waypoint.outlineColor))
            + "|"
            + icon
            + "|"
            + group
            + "|"
            + (waypoint.beam ? "1" : "0");
    }

    /** Characters the chat refuses or that would break the format. */
    private static String clean(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c == '|' ? '/' : c == '§' || c < 32 || c == 127 ? ' ' : c);
        }
        return out.toString()
            .trim();
    }

    /** The waypoint in a chat line, or null if there is none. */
    static Waypoint decode(String text) {
        int start = text.indexOf(PREFIX);
        if (start < 0) {
            return null;
        }
        String[] parts = text.substring(start + PREFIX.length())
            .split("\\|", -1);
        if (parts.length < 9) {
            return null;
        }
        try {
            Waypoint waypoint = new Waypoint(
                parts[0].trim(),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[4].trim()));
            if (!parts[5].trim()
                .isEmpty()) {
                waypoint.outlineColor = Integer.parseInt(parts[5].trim(), 16) & 0xFFFFFF;
            }
            String icon = parts[6].trim();
            int at = icon.lastIndexOf('@');
            if (at > 0) {
                waypoint.iconItem = icon.substring(0, at);
                waypoint.iconMeta = Integer.parseInt(icon.substring(at + 1));
            }
            String group = parts[7].trim();
            waypoint.group = group.isEmpty() ? null : group;
            waypoint.beam = parts[8].trim()
                .startsWith("1");
            return waypoint;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Shows shared waypoints in the chat as their name with an [Add] button instead of the raw line. */
    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.message == null) {
            return;
        }
        String text = event.message.getUnformattedText();
        int start = text.indexOf(PREFIX);
        Waypoint waypoint = start < 0 ? null : decode(text);
        if (waypoint == null) {
            return;
        }
        // Whatever the server put before the line ("<Steve> "), then the waypoint and the button.
        IChatComponent line = new ChatComponentText(text.substring(0, start));
        IChatComponent shared = new ChatComponentText(I18n.format("wayfarmap.share.shared") + " ");
        shared.getChatStyle()
            .setColor(EnumChatFormatting.GRAY);
        line.appendSibling(shared);
        IChatComponent name = new ChatComponentText(waypoint.name.isEmpty() ? "?" : waypoint.name);
        name.getChatStyle()
            .setColor(EnumChatFormatting.AQUA);
        line.appendSibling(name);
        IChatComponent where = new ChatComponentText(
            " (" + waypoint.x + ", " + waypoint.y + ", " + waypoint.z + ", [" + waypoint.dimension + "]) ");
        where.getChatStyle()
            .setColor(EnumChatFormatting.GRAY);
        line.appendSibling(where);
        IChatComponent button = new ChatComponentText("[" + I18n.format("wayfarmap.share.add") + "]");
        ChatStyle style = button.getChatStyle();
        style.setColor(EnumChatFormatting.GREEN);
        style.setChatClickEvent(
            new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " " + text.substring(start)));
        String hint = I18n.format("wayfarmap.share.add_hint");
        if (waypoint.group != null) {
            hint += "\n" + I18n.format("wayfarmap.share.group", waypoint.group);
        }
        style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hint)));
        line.appendSibling(button);
        event.message = line;
    }

    /** Adds the waypoint from a shared chat line: {@code /wayfarmap_add [WFM]|...}. Runs on the client only. */
    public static final class AddCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return COMMAND;
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/" + COMMAND + " <shared waypoint>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            Waypoint waypoint = decode(String.join(" ", args));
            WaypointManager manager = WaypointManager.INSTANCE;
            if (waypoint == null || !manager.isLoaded()) {
                sender.addChatMessage(new ChatComponentText("§c" + I18n.format("wayfarmap.share.invalid")));
                return;
            }
            for (Waypoint existing : manager.getWaypoints()) {
                if (existing.dimension == waypoint.dimension && existing.x == waypoint.x
                    && existing.y == waypoint.y
                    && existing.z == waypoint.z
                    && existing.name.equals(waypoint.name)) {
                    sender.addChatMessage(
                        new ChatComponentText("§7" + I18n.format("wayfarmap.share.exists", waypoint.name)));
                    return;
                }
            }
            if (waypoint.group != null) {
                // Into its group, created if this player doesn't have it.
                waypoint.group = manager.createGroup(waypoint.group).name;
            }
            manager.addWaypoint(waypoint);
            sender.addChatMessage(new ChatComponentText("§a" + I18n.format("wayfarmap.share.added", waypoint.name)));
        }
    }
}
