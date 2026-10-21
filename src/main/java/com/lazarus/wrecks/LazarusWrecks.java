package com.lazarus.wrecks;

import java.util.Queue;
import java.util.EnumMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(LazarusWrecks.ID)
public final class LazarusWrecks {
    public static final String ID = "lazaruswrecks";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Queue<PendingChunk> PENDING = new ConcurrentLinkedQueue<>();
    private static final EnumMap<RoadWreckPlacer.Result, Integer> RESULTS = new EnumMap<>(RoadWreckPlacer.Result.class);
    private static int inspected;
    private static boolean sawServerChunk;
    private static boolean sawNewChunk;
    private static final ForgeConfigSpec CONFIG;
    static final ForgeConfigSpec.BooleanValue ENABLED;
    static final ForgeConfigSpec.DoubleValue URBAN_CHANCE;
    static final ForgeConfigSpec.DoubleValue RURAL_CHANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("generation");
        ENABLED = builder.comment("Place wrecks in newly generated CityWorld road chunks.").define("enabled", true);
        URBAN_CHANCE = builder.comment("Chance per eligible urban road chunk.").defineInRange("urbanChance", 0.25, 0, 1);
        RURAL_CHANCE = builder.comment("Chance per eligible rural road chunk.").defineInRange("ruralChance", 0.05, 0, 1);
        builder.pop();
        CONFIG = builder.build();
    }

    public LazarusWrecks() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(MechanicSkills.class);
        LOGGER.info("Lazarus Wrecks initialized");
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!sawServerChunk) {
            sawServerChunk = true;
            LOGGER.info("Lazarus Wrecks received server chunk events; first dimension={}, new={}", level.dimension().location(), event.isNewChunk());
        }
        if (!event.isNewChunk() || !ENABLED.get()) return;
        if (!level.dimension().equals(ServerLevel.OVERWORLD)) return;
        if (!sawNewChunk) {
            sawNewChunk = true;
            LOGGER.info("Lazarus Wrecks detected new chunks in {}", level.dimension().location());
        }
        PENDING.add(new PendingChunk(level, event.getChunk().getPos(), 0));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int budget = Math.min(8, PENDING.size());
        for (int i = 0; i < budget; i++) {
            PendingChunk pending = PENDING.poll();
            if (pending == null) return;
            if (pending.level.getServer() != event.getServer()) continue;
            ChunkPos pos = pending.pos;
            var loaded = pending.level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (loaded == null) {
                if (pending.attempts < 1200) PENDING.add(new PendingChunk(pending.level, pos, pending.attempts + 1));
                continue;
            }
            WreckData placed = WreckData.get(pending.level);
            if (placed.contains(pos)) continue;
            RoadWreckPlacer.Result result = RoadWreckPlacer.place(pending.level, pos);
            RESULTS.merge(result, 1, Integer::sum);
            if (result == RoadWreckPlacer.Result.PLACED) {
                placed.add(pos);
                LOGGER.info("Lazarus Wrecks placed a wreck at chunk {}, {}", pos.x, pos.z);
            }
            if (++inspected % 32 == 0) {
                LOGGER.info("Lazarus Wrecks inspected {} new chunks: {}", inspected, RESULTS);
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lazaruswrecks")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("backfill")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                .executes(context -> {
                                    ServerLevel level = context.getSource().getLevel();
                                    ChunkPos origin = new ChunkPos(context.getSource().getPlayerOrException().blockPosition());
                                    int radius = IntegerArgumentType.getInteger(context, "radius");
                                    int queued = 0;
                                    for (int x = origin.x - radius; x <= origin.x + radius; x++) {
                                        for (int z = origin.z - radius; z <= origin.z + radius; z++) {
                                            if (level.getChunkSource().getChunkNow(x, z) == null) continue;
                                            PENDING.add(new PendingChunk(level, new ChunkPos(x, z), 0));
                                            queued++;
                                        }
                                    }
                                    int count = queued;
                                    context.getSource().sendSuccess(() -> Component.literal("Lazarus Wrecks queued " + count + " loaded chunks for backfill. See latest.log for results."), false);
                                    return count;
                                }))));
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("Lazarus Wrecks final result: {} chunks inspected: {}", inspected, RESULTS);
        PENDING.clear();
        RESULTS.clear();
        inspected = 0;
        sawServerChunk = false;
        sawNewChunk = false;
    }

    private record PendingChunk(ServerLevel level, ChunkPos pos, int attempts) {}
}
