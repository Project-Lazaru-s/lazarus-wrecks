package com.lazarus.wrecks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mcinterface1201.WrapperWorld;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.AEntityVehicleE_Powered;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Mecânica skill tree of the Project Lazarus modpack (Puffish Skills). The tree's nodes give player tags; this class
 * reads them and grants the tree's XP through the puffish_skills command, so Lazarus Wrecks needs no Puffish API.
 * XP: driving (1 per 150 blocks as the driver) and fixing vehicles. Perks: body repair with scrap metal, hot-wiring
 * the battery with a screwdriver and burning less fuel.
 */
final class MechanicSkills {
    static final String TAG_MECHANIC = "lazarus_mecanico", TAG_BODYWORK = "lazarus_funilaria";
    static final String TAG_HOTWIRE = "lazarus_ligacao", TAG_ECONOMY = "lazarus_economia";
    static final String SCRAP = "apocalypsenow:scrapmetal";
    static final Set<String> SCREWDRIVERS = Set.of("apocalypsenow:screwdriver", "apocalypsenow:phillipsscrewdriver");
    static final double REACH = 5, REPAIR_SHARE = 0.10, BODYWORK_SHARE = 0.20, FUEL_REFUND = 0.15, DRIVE_BLOCKS = 150;
    static final int HOTWIRE_COOLDOWN = 600, FIX_XP = 2;

    /** Last sample per vehicle: x, y, z, fuel. Dropped for vehicles that are gone. */
    private static final Map<UUID, double[]> LAST = new HashMap<>();
    private static final Map<UUID, Double> DRIVEN = new HashMap<>();

    private MechanicSkills() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = event.getServer();
        if (event.phase != TickEvent.Phase.END || server == null || server.getTickCount() % 20 != 0) return;
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels())
            for (EntityVehicleF_Physics vehicle : WrapperWorld.getWrapperFor(level).getEntitiesOfType(EntityVehicleF_Physics.class)) {
                seen.add(vehicle.uniqueUUID);
                sample(server, vehicle);
            }
        LAST.keySet().retainAll(seen);
    }

    private static void sample(MinecraftServer server, EntityVehicleF_Physics vehicle) {
        double fuel = vehicle.fuelTank.getFluidLevel();
        double[] last = LAST.put(vehicle.uniqueUUID, new double[] {vehicle.position.x, vehicle.position.y, vehicle.position.z, fuel});
        IWrapperEntity controller = vehicle.getController();
        if (last == null || controller == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(controller.getID());
        if (player == null) return;
        double driven = DRIVEN.merge(player.getUUID(), MechanicMath.moved(last[0], last[1], last[2],
                vehicle.position.x, vehicle.position.y, vehicle.position.z), Double::sum);
        if (driven >= DRIVE_BLOCKS) {
            DRIVEN.put(player.getUUID(), driven - DRIVE_BLOCKS);
            xp(player, 1);
        }
        double back = MechanicMath.refund(last[3], fuel, FUEL_REFUND);
        if (back > 0 && player.getTags().contains(TAG_ECONOMY) && !vehicle.fuelTank.getFluid().isEmpty()) {
            vehicle.fuelTank.fill(vehicle.fuelTank.getFluid(), vehicle.fuelTank.getFluidMod(), back, true);
            LAST.get(vehicle.uniqueUUID)[3] = vehicle.fuelTank.getFluidLevel();
        }
    }

    /** Sneaking, right click with scrap metal (repair) or a screwdriver (hot-wire) near a vehicle. */
    @SubscribeEvent
    public static void use(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isShiftKeyDown()
                || event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack stack = event.getItemStack();
        String id = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        boolean scrap = SCRAP.equals(id), screwdriver = SCREWDRIVERS.contains(id);
        if (!scrap && !screwdriver) return;
        EntityVehicleF_Physics vehicle = nearest(player);
        if (vehicle == null) return;
        event.setCanceled(true);
        if (scrap) repair(player, stack, vehicle);
        else hotwire(player, stack, vehicle);
    }

    private static EntityVehicleF_Physics nearest(ServerPlayer player) {
        Point3D at = new Point3D(player.getX(), player.getY(), player.getZ());
        EntityVehicleF_Physics best = null;
        double bestDistance = REACH;
        for (EntityVehicleF_Physics vehicle : WrapperWorld.getWrapperFor(player.level()).getEntitiesOfType(EntityVehicleF_Physics.class)) {
            double distance = vehicle.position.distanceTo(at);
            if (distance <= bestDistance) {
                best = vehicle;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void repair(ServerPlayer player, ItemStack scrap, EntityVehicleF_Physics vehicle) {
        if (!player.getTags().contains(TAG_MECHANIC)) {
            say(player, "Você ainda não sabe consertar veículos (Mecânica: Graxa nas mãos).");
            return;
        }
        double health = vehicle.definition.general.health, damage = vehicle.damageVar.getValue();
        double fixed = MechanicMath.repaired(damage, health,
                player.getTags().contains(TAG_BODYWORK) ? BODYWORK_SHARE : REPAIR_SHARE);
        if (fixed <= 0) {
            say(player, "Este veículo não tem dano na lataria.");
            return;
        }
        vehicle.damageVar.setTo(damage - fixed, true);
        if (!player.isCreative()) scrap.shrink(1);
        xp(player, FIX_XP);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.6f, 1.2f);
        say(player, "Lataria consertada: " + Math.round(100 * (health - damage + fixed) / health) + "% da vida.");
    }

    private static void hotwire(ServerPlayer player, ItemStack screwdriver, EntityVehicleF_Physics vehicle) {
        if (!player.getTags().contains(TAG_HOTWIRE)) {
            say(player, "Você ainda não sabe fazer ligação direta (Mecânica: Ligação direta).");
            return;
        }
        if (player.getCooldowns().isOnCooldown(screwdriver.getItem())) return;
        double half = AEntityVehicleE_Powered.BATTERY_DEFAULT_CHARGE / 2;
        if (vehicle.electricPower >= half) {
            say(player, "A bateria ainda tem carga.");
            return;
        }
        vehicle.electricPower = half;
        player.getCooldowns().addCooldown(screwdriver.getItem(), HOTWIRE_COOLDOWN);
        xp(player, FIX_XP);
        player.level().playSound(null, player.blockPosition(), SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.PLAYERS, 0.6f, 1.4f);
        say(player, "Ligação direta: a bateria pegou carga até a metade.");
    }

    private static void say(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }

    private static void xp(ServerPlayer player, int amount) {
        MinecraftServer server = player.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "puffish_skills experience add " + player.getGameProfile().getName() + " puffish_skills:mecanica " + amount);
    }
}
