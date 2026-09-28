package WayFarMap.client.map.iso;

import java.lang.reflect.Field;

import WayFarMap.WayFarMap;

/**
 * The render pass Forge tells block and tile entity renderers ({@code ForgeHooksClient.getWorldRenderPass()},
 * {@code MinecraftForgeClient.getRenderPass()}). Outside the world's own drawing it holds whatever was left there, and
 * renderers that check it draw nothing in the "wrong" pass; {@link FaceRenderer} sets it while taking pictures.
 * Reached by reflection: if a Forge build keeps it elsewhere, nothing is set and pictures are taken as before.
 */
final class RenderPass {

    private static Field worldPass, entityPass;
    private static boolean looked;

    private RenderPass() {}

    private static void look() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            Class<?> hooks = Class.forName("net.minecraftforge.client.ForgeHooksClient");
            worldPass = field(hooks, "worldRenderPass");
            entityPass = field(hooks, "renderPass");
            WayFarMap.LOG.info(
                "3D map pictures: Forge block render pass {}, tile entity render pass {}",
                worldPass != null ? "found" : "not found",
                entityPass != null ? "found" : "not found");
        } catch (Throwable t) {
            WayFarMap.LOG.debug("No Forge render pass for the 3D map's pictures", t);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            if (field.getType() != int.class) {
                return null;
            }
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int get(Field field) {
        if (field == null) {
            return -1;
        }
        try {
            return field.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void set(Field field, int pass) {
        if (field == null) {
            return;
        }
        try {
            field.setInt(null, pass);
        } catch (Throwable ignored) {}
    }

    /** The pass block renderers are told (world chunks), -1 outside it. */
    static int world() {
        look();
        return get(worldPass);
    }

    static void setWorld(int pass) {
        look();
        set(worldPass, pass);
    }

    /** The pass tile entity and entity renderers are told, -1 outside it. */
    static int entity() {
        look();
        return get(entityPass);
    }

    static void setEntity(int pass) {
        look();
        set(entityPass, pass);
    }
}
