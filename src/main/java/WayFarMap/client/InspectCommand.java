package WayFarMap.client;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.iso.BlockInspect;

/**
 * {@code /wfmap3d [x y z]}: writes a report of how one block gets onto the 3D map (the block looked at without
 * coordinates; {@code ~} for the player's own), to send when a block looks wrong there. Client side only.
 */
public final class InspectCommand extends CommandBase {

    public static final String COMMAND = "wfmap3d";

    @Override
    public String getCommandName() {
        return COMMAND;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + COMMAND + " [x y z]";
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
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        int x, y, z;
        if (args.length == 0) {
            MovingObjectPosition hit = mc.objectMouseOver;
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.RED + Lang.format("wayfarmap.inspect.usage", getCommandUsage(sender))));
                return;
            }
            x = hit.blockX;
            y = hit.blockY;
            z = hit.blockZ;
        } else if (args.length == 3) {
            try {
                x = coordinate(args[0], mc.thePlayer.posX);
                y = coordinate(args[1], mc.thePlayer.posY);
                z = coordinate(args[2], mc.thePlayer.posZ);
            } catch (NumberFormatException e) {
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.RED + Lang.format("wayfarmap.inspect.usage", getCommandUsage(sender))));
                return;
            }
        } else {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + Lang.format("wayfarmap.inspect.usage", getCommandUsage(sender))));
            return;
        }
        try {
            File zip = BlockInspect.run(mc.theWorld, x, y, z);
            ChatComponentText message = new ChatComponentText(
                EnumChatFormatting.GREEN + Lang.format("wayfarmap.inspect.done", x, y, z, zip.getName()));
            // Click: the folder with the report.
            message.setChatStyle(
                new ChatStyle().setChatClickEvent(
                    new ClickEvent(
                        ClickEvent.Action.OPEN_FILE,
                        zip.getParentFile()
                            .getAbsolutePath())));
            sender.addChatMessage(message);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not write the 3D map report of a block", t);
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + Lang.format("wayfarmap.inspect.failed", t)));
        }
    }

    /** A coordinate, or {@code ~}/{@code ~n} relative to the player's. */
    private static int coordinate(String arg, double own) {
        if (arg.startsWith("~")) {
            return MathHelper.floor_double(own) + (arg.length() > 1 ? Integer.parseInt(arg.substring(1)) : 0);
        }
        return Integer.parseInt(arg);
    }
}
