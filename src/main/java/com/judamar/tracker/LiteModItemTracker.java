package com.judamar.tracker;

import com.mumfrey.liteloader.LiteMod;
import com.mumfrey.liteloader.OutboundChatFilter;
import com.mumfrey.liteloader.PluginChannelListener;
import com.mumfrey.liteloader.PostRenderListener;
import com.mumfrey.liteloader.core.ClientPluginChannels;
import com.mumfrey.liteloader.core.PluginChannels;
import com.mumfrey.liteloader.Tickable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.opengl.GL11;

/**
 * Client-only item tracker. /track [hand | <id> [meta] | off].
 * Sees: dropped items, item frames, items held/worn by loaded entities, and containers YOU opened (remembered by position).
 * Cannot see: unopened containers, hoppers, other players' inventories, unloaded chunks (needs server support).
 */
public class LiteModItemTracker implements LiteMod, Tickable, OutboundChatFilter, PostRenderListener, PluginChannelListener {
    private ItemStack tracked; // count 1; null = off
    private final Map<BlockPos, Boolean> containers = new HashMap<>(); // pos -> still holds the item (as of last opening)
    private boolean wasOpen;

    // --- Carpet server mode (channel itemtracker:main). Preferred over local/client scans whenever the server answers. ---
    private static final String CHANNEL = "itemtracker:main";
    private static final double[][] SIZE = {{.15, .15}, {.35, .35}, {.4, .9}, {.5, .5}}; // half width/height per kind: dropped, frame, entity, container
    private static final float[][] KIND = {{1f, .9f, 0f}, {1f, .5f, 0f}, {0f, .9f, 1f}, {.2f, 1f, .2f}}; // dropped, frame, entity, container
    private volatile List<double[]> remoteTargets = new ArrayList<>();
    private volatile int remoteDim;
    private volatile long remoteSeen = -1000;
    private long ticks;
    private boolean dirty;
    private volatile String warn; // message from the packet thread, printed on the next tick
    private long trackedAt;
    private volatile boolean warnedSilent;

    @Override public List<String> getChannels() { return java.util.Collections.singletonList(CHANNEL); }

    @Override
    public void onCustomPayload(String channel, net.minecraft.network.PacketBuffer b) {
        try {
            int dim = b.readInt(), n = b.readInt();
            if (n < 0) { if (!warnedSilent) { warnedSilent = true; warn = "Server refused: it needs the Carpet rule modItemTracker=true and you must be op."; } return; }
            List<double[]> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
                int k = Math.max(0, Math.min(3, b.readByte()));
                float[] c = KIND[k];
                l.add(new double[]{x, y, z, c[0], c[1], c[2], SIZE[k][0], SIZE[k][1], b.readInt()});
            }
            remoteDim = dim; remoteTargets = l; remoteSeen = ticks;
        } catch (RuntimeException e) { /* malformed packet: ignore */ }
    }

    /** Tells the server what to track (empty stack = off). Ignored by servers without the Carpet module. */
    private void sendTracked() {
        net.minecraft.network.PacketBuffer b = new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
        b.writeItemStack(tracked == null ? ItemStack.EMPTY : tracked);
        ClientPluginChannels.sendMessage(CHANNEL, b, PluginChannels.ChannelPolicy.DISPATCH_ALWAYS);
    }

    @Override public String getName() { return "ItemTracker"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public void init(File configPath) { }
    @Override public void upgradeSettings(String v, File c, File o) { }

    private boolean matches(ItemStack s) {
        if (tracked == null || s == null || s.isEmpty()) return false;
        if (s.getItem() != tracked.getItem()) return false;
        if (s.getItem().isDamageable()) return true; // ponytail: ignores damage/NBT for tools, add strict NBT mode if needed
        return ItemStack.areItemsEqual(s, tracked) && ItemStack.areItemStackTagsEqual(s, tracked);
    }

    @Override
    public boolean onSendChatMessage(String msg) {
        if (!msg.startsWith("/track")) return true;
        Minecraft mc = Minecraft.getMinecraft();
        String[] a = msg.trim().split("\\s+");
        String r;
        if (a.length > 1 && a[1].equals("off")) { tracked = null; dirty = true; trackedAt = ticks; warnedSilent = false; containers.clear(); r = "Tracking off."; }
        else if (a.length > 1 && !a[1].equals("hand")) {
            Item it = Item.getByNameOrId(a[1]);
            if (it == null) r = "Unknown item: " + a[1];
            else {
                int meta = 0;
                try { meta = a.length > 2 ? Integer.parseInt(a[2]) : 0; } catch (NumberFormatException e) { }
                tracked = new ItemStack(it, 1, meta); dirty = true; trackedAt = ticks; warnedSilent = false; containers.clear();
                r = "Tracking " + tracked.getDisplayName();
            }
        } else {
            ItemStack h = mc.player.getHeldItemMainhand();
            if (h.isEmpty()) r = "Hold an item or use /track <id> [meta] | off";
            else { tracked = h.copy(); tracked.setCount(1); dirty = true; trackedAt = ticks; warnedSilent = false; containers.clear(); r = "Tracking " + tracked.getDisplayName(); }
        }
        mc.player.sendMessage(new TextComponentString("[Tracker] " + r));
        return false;
    }

    /** Remember the container block the player opened, and whether it holds the item. */
    @Override
    public void onTick(Minecraft mc, float pt, boolean inGame, boolean clock) {
        if (clock) ticks++; // onTick runs every frame; only count real game ticks (20/s) or the 20-tick freshness window shrinks at high FPS
        if (inGame && mc.getIntegratedServer() == null && (dirty || (tracked != null && ticks % 100 == 0))) { sendTracked(); dirty = false; }
        net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
        if (inGame && clock && tracked != null && srv != null && mc.player != null && mc.player.ticksExisted % 2 == 0) {
            final net.minecraft.entity.player.EntityPlayer me = mc.player;
            final int dim = mc.player.dimension;
            srv.addScheduledTask(() -> { // ponytail: scan every 2 ticks, all loaded entities+tile entities; throttle if heavy worlds lag
                net.minecraft.world.World w = srv.getWorld(dim);
                Entity self = w.getEntityByID(me.getEntityId());
                scanServer(w, self);
            });
        }
        if (tracked == null) serverTargets = new ArrayList<>();
        if (inGame && mc.player != null) {
            String w = warn; warn = null;
            if (w == null && tracked != null && srv == null && !warnedSilent && ticks - trackedAt > 100 && ticks - remoteSeen > 100) {
                w = "The server did not answer (no Carpet ItemTracker module?). Client-only mode: only containers you open are known.";
                warnedSilent = true;
            }
            if (w != null) mc.player.sendMessage(new TextComponentString("[Tracker] " + w));
        }
        // Own inventory / creative screens are not block containers: ignore them or they'd mark the looked-at chest as empty.
        boolean open = inGame && tracked != null && mc.currentScreen instanceof GuiContainer
                && !(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiInventory)
                && !(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainerCreative);
        if (open && !wasOpen) {
            RayTraceResult r = mc.objectMouseOver;
            pendingPos = r != null && r.typeOfHit == RayTraceResult.Type.BLOCK ? r.getBlockPos() : null;
        }
        if (open && pendingPos != null) {
            boolean has = false;
            for (Slot s : ((GuiContainer) mc.currentScreen).inventorySlots.inventorySlots)
                if (s.inventory != mc.player.inventory && matches(s.getStack())) { has = true; break; }
            containers.put(pendingPos, has);
        }
        if (!open) pendingPos = null;
        wasOpen = open;
    }
    private BlockPos pendingPos;

    /** Singleplayer: result of the last scan of the integrated server's real world (x,y,z,r,g,b). */
    private volatile List<double[]> serverTargets = new ArrayList<>();

    private boolean holds(net.minecraft.inventory.IInventory inv) {
        for (int i = 0; i < inv.getSizeInventory(); i++) if (matches(inv.getStackInSlot(i))) return true;
        return false;
    }

    /** Runs on the server thread: sees inside hoppers, chests, minecarts and players without opening anything. */
    private void scanServer(net.minecraft.world.World w, Entity self) {
        List<double[]> out = new ArrayList<>();
        for (Entity e : new ArrayList<>(w.loadedEntityList)) {
            if (e == self) continue; // own inventory: nothing to point at
            boolean hit = false; float r = 0f, g = 0.9f, b = 1f;
            if (e instanceof EntityItem) { hit = matches(((EntityItem) e).getItem()); r = 1f; b = 0f; }
            else if (e instanceof EntityItemFrame) { hit = matches(((EntityItemFrame) e).getDisplayedItem()); r = 1f; g = 0.5f; b = 0f; }
            else if (e instanceof net.minecraft.inventory.IInventory) hit = holds((net.minecraft.inventory.IInventory) e); // chest/hopper minecarts
            else if (e instanceof net.minecraft.entity.player.EntityPlayer) hit = holds(((net.minecraft.entity.player.EntityPlayer) e).inventory);
            else if (e instanceof EntityLivingBase) { for (ItemStack st : ((EntityLivingBase) e).getEquipmentAndArmor()) if (matches(st)) hit = true; }
            if (hit) out.add(new double[]{e.posX, e.posY + e.height / 2, e.posZ, r, g, b, e.width / 2, e.height / 2});
        }
        for (net.minecraft.tileentity.TileEntity te : new ArrayList<>(w.loadedTileEntityList))
            if (te instanceof net.minecraft.inventory.IInventory && holds((net.minecraft.inventory.IInventory) te)) {
                BlockPos p = te.getPos();
                out.add(new double[]{p.getX() + .5, p.getY() + .5, p.getZ() + .5, 0.2, 1, 0.2, .5, .5});
            }
        serverTargets = out;
    }

    @Override
    public void onPostRenderEntities(float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity cam = mc.getRenderViewEntity();
        if (tracked == null || mc.world == null || cam == null) return;
        boolean remote = ticks - remoteSeen <= 20; // Carpet server is answering: its data is complete
        boolean sp = remote || mc.getIntegratedServer() != null;
        List<double[]> targets = remote ? (remoteDim == mc.player.dimension ? new ArrayList<>(remoteTargets) : new ArrayList<double[]>())
                : sp ? new ArrayList<>(serverTargets) : new ArrayList<>(); // x,y,z,r,g,b
        if (!sp) for (Entity e : mc.world.loadedEntityList) {
            if (e == mc.player) continue;
            if (e instanceof EntityItem) { if (matches(((EntityItem) e).getItem())) add(targets, e, pt, 1f, 0.9f, 0f); }
            else if (e instanceof EntityItemFrame) { if (matches(((EntityItemFrame) e).getDisplayedItem())) add(targets, e, pt, 1f, 0.5f, 0f); }
            else if (e instanceof EntityLivingBase) {
                boolean has = false;
                for (ItemStack s : ((EntityLivingBase) e).getEquipmentAndArmor()) if (matches(s)) { has = true; break; }
                if (has) add(targets, e, pt, 0f, 0.9f, 1f);
            }
        }
        if (!sp) for (Map.Entry<BlockPos, Boolean> c : containers.entrySet())
            if (c.getValue() && !mc.world.isAirBlock(c.getKey()))
                targets.add(new double[]{c.getKey().getX() + .5, c.getKey().getY() + .5, c.getKey().getZ() + .5, 0.2, 1, 0.2, .5, .5});
        // Server positions arrive per tick without interpolation: when the entity is also loaded here, use its smooth client position.
        for (double[] t : targets) {
            if (t.length < 9 || t[8] < 0) continue;
            Entity e = mc.world.getEntityByID((int) t[8]);
            if (e == null) continue;
            t[0] = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
            t[1] = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + e.height / 2;
            t[2] = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
            t[6] = e.width / 2; t[7] = e.height / 2;
        }
        if (targets.isEmpty()) return;

        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * pt;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * pt; // feet: MC's camera matrix already applies the eye-height offset
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * pt;
        Vec3d look = cam.getLook(pt);
        // Camera-relative origin: a point just ahead of the eye along the view ray projects exactly on the crosshair.
        double ox = look.x * 0.3, oy = cam.getEyeHeight() + look.y * 0.3, oz = look.z * 0.3;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glLineWidth(2f);
        GL11.glBegin(GL11.GL_LINES);
        for (double[] t : targets) {
            GlStateManager.color((float) t[3], (float) t[4], (float) t[5], 0.9f);
            GL11.glVertex3d(ox, oy, oz);
            GL11.glVertex3d(t[0] - cx, t[1] - cy, t[2] - cz);
        }
        GL11.glEnd();
        // hitbox around the item / entity and outline of the container that holds it
        GL11.glBegin(GL11.GL_LINES);
        for (double[] t : targets) {
            GlStateManager.color((float) t[3], (float) t[4], (float) t[5], 1f);
            double m = 0.01; // slight margin so block outlines don't z-fight their own faces
            box(t[0] - cx, t[1] - cy, t[2] - cz, t[6] + m, t[7] + m);
        }
        GL11.glEnd();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    /** Wireframe box centred on (x,y,z) with half width hw and half height hh. */
    private static void box(double x, double y, double z, double hw, double hh) {
        double[] xs = {x - hw, x + hw}, ys = {y - hh, y + hh}, zs = {z - hw, z + hw};
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            GL11.glVertex3d(xs[0], ys[i], zs[j]); GL11.glVertex3d(xs[1], ys[i], zs[j]); // edges along X
            GL11.glVertex3d(xs[i], ys[0], zs[j]); GL11.glVertex3d(xs[i], ys[1], zs[j]); // along Y
            GL11.glVertex3d(xs[i], ys[j], zs[0]); GL11.glVertex3d(xs[i], ys[j], zs[1]); // along Z
        }
    }

    private static void add(List<double[]> l, Entity e, float pt, float r, float g, float b) {
        l.add(new double[]{e.lastTickPosX + (e.posX - e.lastTickPosX) * pt,
                           e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + e.height / 2,
                           e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt, r, g, b, e.width / 2, e.height / 2});
    }

    @Override public void onPostRender(float pt) { }
}
