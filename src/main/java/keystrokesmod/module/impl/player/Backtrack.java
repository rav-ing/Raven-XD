package keystrokesmod.module.impl.player;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import keystrokesmod.event.PreTickEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.mixins.impl.network.S14PacketEntityAccessor;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.script.classes.Vec3;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.backtrack.TimedPacket;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.NotNull;

public class Backtrack extends Module {
    public static final Color color = new Color(72, 125, 227);

    private final SliderSetting minLatency = new SliderSetting("Min latency", 50, 1, 1000, 1);
    private final SliderSetting maxLatency = new SliderSetting("Max latency", 100, 1, 1000, 1);
    private final SliderSetting minDistance = new SliderSetting("Min distance", 0.0, 0.0, 3.0, 0.1);
    private final SliderSetting maxDistance = new SliderSetting("Max distance", 6.0, 0.0, 10.0, 0.1);
    private final SliderSetting stopOnTargetHurtTime = new SliderSetting("Stop on target HurtTime", -1, -1, 10, 1);
    private final SliderSetting stopOnSelfHurtTime = new SliderSetting("Stop on self HurtTime", -1, -1, 10, 1);

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private final List<Packet<?>> skipPackets = new ArrayList<>();
    private Vec3 vec3;
    private EntityPlayer target;

    private int currentLatency = 0;

    public Backtrack() {
        super("Backtrack", category.player);
        this.registerSetting(new DescriptionSetting("Allows you to hit past opponents."));
        this.registerSetting(minLatency);
        this.registerSetting(maxLatency);
        this.registerSetting(minDistance);
        this.registerSetting(maxDistance);
        this.registerSetting(stopOnTargetHurtTime);
        this.registerSetting(stopOnSelfHurtTime);
    }

    @Override
    public String getInfo() {
        return (currentLatency == 0 ? (int) maxLatency.getInput() : currentLatency) + "ms";
    }

    @Override
    public void guiUpdate() {
        Utils.correctValue(minLatency, maxLatency);
        Utils.correctValue(minDistance, maxDistance);
    }

    @Override
    public void onEnable() {
        packetQueue.clear();
        skipPackets.clear();
        vec3 = null;
        target = null;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (mc.thePlayer == null)
            return;

        releaseAll();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        try {
            final double distance = vec3.distanceTo(mc.thePlayer);
            if (distance > maxDistance.getInput()
                    || distance < minDistance.getInput()
            ) {
                target = null;
                vec3 = null;
            }

        } catch (NullPointerException ignored) {
        }

    }

    @SubscribeEvent
    public void onPreTick(PreTickEvent e) {
        while (!packetQueue.isEmpty()) {
            try {
                if (packetQueue.element().getCold().getCum(currentLatency)) {
                    Packet<NetHandlerPlayClient> packet = (Packet<NetHandlerPlayClient>) packetQueue.remove().getPacket();
                    skipPackets.add(packet);
                    PacketUtils.receivePacket(packet);
                } else {
                    break;
                }
            } catch (NullPointerException ignored) {
            }
        }

        if (packetQueue.isEmpty() && target != null) {
            vec3 = new Vec3(target.getPositionVector());
        }
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (target == null)
            return;

        Blink.drawBox(vec3.toVec3());
    }

    @SubscribeEvent
    public void onAttack(@NotNull AttackEntityEvent e) {
        if (e.target instanceof EntityPlayer) {

            if (target != null && e.target == target)
                return;

            target = (EntityPlayer) e.target;
            vec3 = new Vec3(e.target.getPositionVector());
        }

        currentLatency = (int) (Math.random() * (maxLatency.getInput() - minLatency.getInput()) + minLatency.getInput());
    }

    @SubscribeEvent
    public void onReceivePacket(@NotNull ReceivePacketEvent e) {
        if (!Utils.nullCheck()) return;
        Packet<?> p = e.getPacket();
        if (skipPackets.contains(p)) {
            skipPackets.remove(p);
            return;
        }

        if (target != null && stopOnTargetHurtTime.getInput() != -1 && target.hurtTime == stopOnTargetHurtTime.getInput()) {
            releaseAll();
            return;
        }
        if (stopOnSelfHurtTime.getInput() != -1 && mc.thePlayer.hurtTime == stopOnSelfHurtTime.getInput()) {
            releaseAll();
            return;
        }

        try {
            if (mc.thePlayer == null || mc.thePlayer.ticksExisted < 20) {
                packetQueue.clear();
                return;
            }

            if (target == null) {
                releaseAll();
                return;
            }

            if (e.isCanceled())
                return;

            if (p instanceof S19PacketEntityStatus
                    || p instanceof S02PacketChat
                    || p instanceof S0BPacketAnimation
                    || p instanceof S06PacketUpdateHealth
            )
                return;

            if (p instanceof S08PacketPlayerPosLook || p instanceof S40PacketDisconnect) {
                releaseAll();
                target = null;
                vec3 = null;
                return;

            } else if (p instanceof S13PacketDestroyEntities) {
                S13PacketDestroyEntities wrapper = (S13PacketDestroyEntities) p;
                for (int id : wrapper.getEntityIDs()) {
                    if (id == target.getEntityId()) {
                        target = null;
                        vec3 = null;
                        releaseAll();
                        return;
                    }
                }
            } else if (p instanceof S14PacketEntity) {
                S14PacketEntity wrapper = (S14PacketEntity) p;
                if (((S14PacketEntityAccessor) wrapper).getEntityId() == target.getEntityId()) {
                    vec3 = vec3.add(wrapper.func_149062_c() / 32.0D, wrapper.func_149061_d() / 32.0D,
                            wrapper.func_149064_e() / 32.0D);
                }
            } else if (p instanceof S18PacketEntityTeleport) {
                S18PacketEntityTeleport wrapper = (S18PacketEntityTeleport) p;
                if (wrapper.getEntityId() == target.getEntityId()) {
                    vec3 = new Vec3(wrapper.getX() / 32.0D, wrapper.getY() / 32.0D, wrapper.getZ() / 32.0D);
                }
            }

            packetQueue.add(new TimedPacket(p));
            e.setCanceled(true);
        } catch (NullPointerException ignored) {

        }
    }

    private void releaseAll() {
        if (!packetQueue.isEmpty()) {
            for (TimedPacket timedPacket : packetQueue) {
                Packet<NetHandlerPlayClient> packet = (Packet<NetHandlerPlayClient>) timedPacket.getPacket();
                skipPackets.add(packet);
                PacketUtils.receivePacket(packet);
            }
            packetQueue.clear();
        }
    }

}