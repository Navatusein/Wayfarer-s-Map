package WayFarMap.share;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        channel.registerMessage(TeammatesToClient.class, Teammates.class, 5, Side.CLIENT);
        channel.registerMessage(LoadBatchToClient.class, LoadBatch.class, 6, Side.CLIENT);
        channel.registerMessage(LoadDoneToServer.class, LoadDone.class, 7, Side.SERVER);
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
        /**
         * The records compressed, made on the first send: the server sends the same message to every teammate,
         * and compressing it once instead of once per teammate spares the server's tick.
         */
        private byte[] encoded;

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
            byte[] data = new byte[Math.max(0, Math.min(buf.readInt(), buf.readableBytes()))];
            buf.readBytes(data);
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
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
            byte[] data = encoded;
            if (data == null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                    out.writeShort(records.size());
                    for (ChunkRecord record : records) {
                        record.write(out);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                data = bytes.toByteArray();
                encoded = data;
            }
            buf.writeInt(dimension);
            buf.writeBoolean(backfill);
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }
    }

    /** Where the player's online teammates are (the player not included); sent twice a second. */
    public static final class Teammates implements IMessage {

        /** One teammate. */
        public static final class Mate {

            public UUID id;
            public String name;
            public int dimension;
            public double x, y, z;
            public float yaw;
        }

        public final List<Mate> mates = new ArrayList<>();

        @Override
        public void fromBytes(ByteBuf buf) {
            int count = Math.min(buf.readShort(), 256);
            for (int i = 0; i < count; i++) {
                Mate mate = new Mate();
                mate.id = new UUID(buf.readLong(), buf.readLong());
                byte[] name = new byte[Math.min(64, Math.max(0, buf.readShort()))];
                buf.readBytes(name);
                mate.name = new String(name, StandardCharsets.UTF_8);
                mate.dimension = buf.readInt();
                mate.x = buf.readDouble();
                mate.y = buf.readDouble();
                mate.z = buf.readDouble();
                mate.yaw = buf.readFloat();
                mates.add(mate);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(mates.size());
            for (Mate mate : mates) {
                buf.writeLong(mate.id.getMostSignificantBits());
                buf.writeLong(mate.id.getLeastSignificantBits());
                byte[] name = mate.name.getBytes(StandardCharsets.UTF_8);
                buf.writeShort(name.length);
                buf.writeBytes(name);
                buf.writeInt(mate.dimension);
                buf.writeDouble(mate.x);
                buf.writeDouble(mate.y);
                buf.writeDouble(mate.z);
                buf.writeFloat(mate.yaw);
            }
        }
    }

    /**
     * A batch of chunks of {@code /wf chunkload} was sent to the player (as the game sends chunks, just before this):
     * the client maps the inner ones (the outer ring is there for their neighbours), lets them all go, and answers
     * with {@link LoadDone}.
     */
    public static final class LoadBatch implements IMessage {

        public int job, index, dimension;
        public boolean with3d;
        /** Inner chunks to map and the chunks sent (inner and a ring around), inclusive. */
        public int innerX0, innerZ0, innerX1, innerZ1, outerX0, outerZ0, outerX1, outerZ1;
        /** Chunks of the whole area done before this batch, and in all. */
        public long doneBefore, total;
        /** The server's view distance: chunks this close to the player are the game's, not let go. */
        public int viewDistance;
        /**
         * For the log: chunks sent, those loaded again because the server had let them go before they were sent,
         * those it could not give, and the server's time on the batch (from its start, and working on it).
         */
        public int sent, reloaded, missing;
        public int serverMs, workMs;

        @Override
        public void fromBytes(ByteBuf buf) {
            job = buf.readInt();
            index = buf.readInt();
            dimension = buf.readInt();
            with3d = buf.readBoolean();
            innerX0 = buf.readInt();
            innerZ0 = buf.readInt();
            innerX1 = buf.readInt();
            innerZ1 = buf.readInt();
            outerX0 = buf.readInt();
            outerZ0 = buf.readInt();
            outerX1 = buf.readInt();
            outerZ1 = buf.readInt();
            doneBefore = buf.readLong();
            total = buf.readLong();
            viewDistance = buf.readInt();
            sent = buf.readInt();
            reloaded = buf.readInt();
            missing = buf.readInt();
            serverMs = buf.readInt();
            workMs = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(job);
            buf.writeInt(index);
            buf.writeInt(dimension);
            buf.writeBoolean(with3d);
            buf.writeInt(innerX0);
            buf.writeInt(innerZ0);
            buf.writeInt(innerX1);
            buf.writeInt(innerZ1);
            buf.writeInt(outerX0);
            buf.writeInt(outerZ0);
            buf.writeInt(outerX1);
            buf.writeInt(outerZ1);
            buf.writeLong(doneBefore);
            buf.writeLong(total);
            buf.writeInt(viewDistance);
            buf.writeInt(sent);
            buf.writeInt(reloaded);
            buf.writeInt(missing);
            buf.writeInt(serverMs);
            buf.writeInt(workMs);
        }
    }

    /** The client mapped a batch of {@code /wf chunkload}: the server may send the next one. */
    public static final class LoadDone implements IMessage {

        public int job, index;

        public LoadDone() {}

        public LoadDone(int job, int index) {
            this.job = job;
            this.index = index;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            job = buf.readInt();
            index = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(job);
            buf.writeInt(index);
        }
    }

    // Handlers run on the network thread; both sides only queue the message for their own thread.

    public static final class LoadBatchToClient implements IMessageHandler<LoadBatch, IMessage> {

        @Override
        public IMessage onMessage(LoadBatch message, MessageContext context) {
            WayFarMap.proxy.receiveChunkLoad(message);
            return null;
        }
    }

    public static final class LoadDoneToServer implements IMessageHandler<LoadDone, IMessage> {

        @Override
        public IMessage onMessage(LoadDone message, MessageContext context) {
            ChunkLoadServer.INSTANCE.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

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

    public static final class TeammatesToClient implements IMessageHandler<Teammates, IMessage> {

        @Override
        public IMessage onMessage(Teammates message, MessageContext context) {
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
