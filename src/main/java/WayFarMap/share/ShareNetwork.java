package WayFarMap.share;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.entity.player.EntityPlayerMP;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * The team map channel. The client says hello when it joins; a server with this mod and ServerUtilities answers,
 * and from then on the client uploads the chunks it maps and gets its teammates' ones. Servers without the mod
 * never answer, so nothing else is ever sent there.
 */
public final class ShareNetwork {

    public static final int PROTOCOL = 2;
    /** Records per message from the server: at most ~50 KB even uncompressed. */
    public static final int MAX_RECORDS = 32;
    /**
     * Records per upload: client packets are limited to 32 KB, and 16 records stay under it even if they don't
     * compress at all.
     */
    public static final int MAX_UPLOAD_RECORDS = 16;

    private static SimpleNetworkWrapper channel;

    private ShareNetwork() {}

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(WayFarMap.MODID);
        channel.registerMessage(HelloToServer.class, Hello.class, 0, Side.SERVER);
        channel.registerMessage(HelloToClient.class, Hello.class, 1, Side.CLIENT);
        channel.registerMessage(ChunksToServer.class, Chunks.class, 2, Side.SERVER);
        channel.registerMessage(ChunksToClient.class, Chunks.class, 3, Side.CLIENT);
    }

    public static void sendToServer(IMessage message) {
        channel.sendToServer(message);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        channel.sendTo(message, player);
    }

    /**
     * Client hello (asks whether the server shares team maps) and the server's answer, which it sends again
     * whenever the player's team changes: {@code team} is the team id, empty without a team.
     */
    public static final class Hello implements IMessage {

        public int protocol = PROTOCOL;
        public String team = "";

        public Hello() {}

        public Hello(String team) {
            this.team = team == null ? "" : team;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            protocol = buf.readInt();
            byte[] bytes = new byte[Math.min(64, Math.max(0, buf.readShort()))];
            buf.readBytes(bytes);
            team = new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(protocol);
            byte[] bytes = team.getBytes(StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    /** Map chunks of one dimension: uploads from a client, or teammates' chunks from the server. */
    public static final class Chunks implements IMessage {

        public int dimension;
        /** An upload of the map the player already had (any dimension, anywhere), not of chunks just mapped. */
        public boolean backfill;
        public final List<ChunkRecord> records = new ArrayList<>();

        public Chunks() {}

        public Chunks(int dimension, List<ChunkRecord> records) {
            this.dimension = dimension;
            this.records.addAll(records);
        }

        public Chunks(int dimension, List<ChunkRecord> records, boolean backfill) {
            this(dimension, records);
            this.backfill = backfill;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            dimension = buf.readInt();
            backfill = buf.readBoolean();
            byte[] data = new byte[Math.min(buf.readInt(), buf.readableBytes())];
            buf.readBytes(data);
            try (DataInputStream in = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(data)))) {
                int count = in.readShort();
                for (int i = 0; i < count && i < MAX_RECORDS * 2; i++) {
                    records.add(ChunkRecord.read(in));
                }
            } catch (IOException e) {
                WayFarMap.LOG.warn("Bad team map message", e);
                records.clear();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                out.writeShort(records.size());
                for (ChunkRecord record : records) {
                    record.write(out);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            buf.writeInt(dimension);
            buf.writeBoolean(backfill);
            buf.writeInt(bytes.size());
            buf.writeBytes(bytes.toByteArray());
        }
    }

    // Handlers run on the network thread; both sides only queue the message for their own thread.

    public static final class HelloToServer implements IMessageHandler<Hello, IMessage> {

        @Override
        public IMessage onMessage(Hello message, MessageContext context) {
            TeamMapServer.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    public static final class ChunksToServer implements IMessageHandler<Chunks, IMessage> {

        @Override
        public IMessage onMessage(Chunks message, MessageContext context) {
            TeamMapServer.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    /** The client proxy passes these to the client's team map (the dedicated server never gets them). */
    public static final class HelloToClient implements IMessageHandler<Hello, IMessage> {

        @Override
        public IMessage onMessage(Hello message, MessageContext context) {
            WayFarMap.proxy.receiveTeamMap(message);
            return null;
        }
    }

    public static final class ChunksToClient implements IMessageHandler<Chunks, IMessage> {

        @Override
        public IMessage onMessage(Chunks message, MessageContext context) {
            WayFarMap.proxy.receiveTeamMap(message);
            return null;
        }
    }
}
