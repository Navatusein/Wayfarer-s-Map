package WayFarMap.share;

import io.netty.buffer.ByteBuf;

/**
 * An AE2 wireless access point: where it is, its range in blocks (0 when not known) and whether it works (powered,
 * with a channel). A terminal reaches it within {@code range} of (x, y, z) in 3D, measured from the block's corner,
 * as AE2 does.
 */
public final class WirelessPoint {

    public final int dimension, x, y, z;
    public final float range;
    public final boolean active;

    public WirelessPoint(int dimension, int x, int y, int z, float range, boolean active) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = range;
        this.active = active;
    }

    static WirelessPoint read(ByteBuf buf) {
        return new WirelessPoint(
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readFloat(),
            buf.readBoolean());
    }

    void write(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeFloat(range);
        buf.writeBoolean(active);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WirelessPoint)) {
            return false;
        }
        WirelessPoint p = (WirelessPoint) o;
        return dimension == p.dimension && x == p.x && y == p.y && z == p.z && range == p.range && active == p.active;
    }

    @Override
    public int hashCode() {
        return ((dimension * 31 + x) * 31 + y) * 31 + z;
    }
}
