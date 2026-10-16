package com.lazarus.wrecks;

import java.util.ArrayList;
import java.util.Random;

import mcinterface1201.WrapperWorld;
import me.daddychurchill.CityWorld.CityWorldGenerator;
import me.daddychurchill.CityWorld.Plats.PlatLot;
import me.daddychurchill.CityWorld.Plats.RoadLot;
import me.daddychurchill.CityWorld.Support.PlatMap;
import me.daddychurchill.CityWorld.worldgen.CityWorldChunkGenerator;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.items.components.AItemPart;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.packloading.PackParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class RoadWreckPlacer {
    enum Result { OTHER_GENERATOR, OTHER_STYLE, NOT_ROAD, CHANCE, NO_CLEAR_SPOT, NO_VEHICLE, PLACED }

    private static final String[][] CARS = {
            {"scout", "_blue", "_white", "_red", "_olive", "_gray", "_brown"},
            {"merc230", "_blue", "_white", "_brown", "_black", "_gray", "_olive"},
            {"fordmustang69", "_red", "_black", "_silver", "_orange", "_white"}
    };

    private RoadWreckPlacer() {}

    static Result place(ServerLevel level, ChunkPos chunk) {
        if (!(level.getChunkSource().getGenerator() instanceof CityWorldChunkGenerator generator)) return Result.OTHER_GENERATOR;
        CityWorldGenerator cityWorld = generator.getContext(level);
        if (!cityWorld.isApocalypseStyle()) return Result.OTHER_STYLE;

        PlatMap map = cityWorld.getPlatMap(chunk.x, chunk.z);
        PlatLot lot = map.getMapLot(chunk.x, chunk.z);
        if (!(lot instanceof RoadLot road)) return Result.NOT_ROAD;

        int localX = chunk.x - map.originX;
        int localZ = chunk.z - map.originZ;
        boolean[] streets = road.getStreetDirections(map, localX, localZ);
        long seed = WreckRoll.seed(level.getSeed(), chunk.x, chunk.z);
        boolean urban = hasBuildingNeighbor(map, localX, localZ);
        double chance = urban ? LazarusWrecks.URBAN_CHANCE.get() : LazarusWrecks.RURAL_CHANCE.get();
        if (!WreckRoll.selected(seed, chance)) return Result.CHANCE;

        Random random = new Random(seed);
        boolean eastWest = streets[0] && (!streets[1] || random.nextBoolean());
        Direction facing = eastWest
                ? (random.nextBoolean() ? Direction.EAST : Direction.WEST)
                : (random.nextBoolean() ? Direction.NORTH : Direction.SOUTH);
        BlockPos center = null;
        int start = random.nextInt(6);
        for (int i = 0; i < 6; i++) {
            int index = (start + i) % 6;
            int longitudinal = new int[] {4, 8, 11}[index / 2];
            int lateral = index % 2 == 0 ? 4 : 11;
            int centerX = chunk.getMinBlockX() + (eastWest ? longitudinal : lateral);
            int centerZ = chunk.getMinBlockZ() + (eastWest ? lateral : longitudinal);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
            BlockPos candidate = new BlockPos(centerX, y, centerZ);
            if (clearAndLevel(level, candidate, facing)) {
                center = candidate;
                break;
            }
        }
        if (center == null) return Result.NO_CLEAR_SPOT;

        String[] model = CARS[random.nextInt(CARS.length)];
        ItemVehicle item = PackParser.getItem("mtsofficialpack", model[0], model[1 + random.nextInt(model.length - 1)]);
        if (item == null) return Result.NO_VEHICLE;

        // Spawn the real Immersive Vehicles entity, then install its default body
        // details and wheels. Remove the engine to leave a repairable wreck.
        WrapperWorld world = WrapperWorld.getWrapperFor(level);
        EntityVehicleF_Physics vehicle = new EntityVehicleF_Physics(world, null, item, null);
        vehicle.position.set(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        vehicle.prevPosition.set(vehicle.position);
        vehicle.orientation.setToAngles(new Point3D(0, facing.toYRot() + 90, 0));
        vehicle.prevOrientation.set(vehicle.orientation);
        vehicle.motion.set(0, 0, 0);
        vehicle.prevMotion.set(vehicle.motion);
        vehicle.electricPower = 0;
        vehicle.damageVar.setTo(vehicle.definition.general.health * (0.35 + random.nextDouble() * 0.35), false);
        world.spawnEntity(vehicle);
        vehicle.addPartsPostAddition(null, null);
        vehicle.fuelTank.clear();
        for (PartEngine engine : new ArrayList<>(vehicle.engines)) {
            vehicle.removePart(engine, false, true);
        }
        AItemPart wheel = PackParser.getItem("mtsofficialpack", "wheelmedium", "");
        if (wheel != null) {
            int missingWheel = random.nextInt(5) == 0 ? random.nextInt(4) : -1;
            for (int slot = 0; slot < 4; slot++) {
                if (slot != missingWheel) vehicle.addPartFromStack(wheel.getNewStack(null), null, slot, false, true);
            }
        }
        return Result.PLACED;
    }

    private static boolean hasBuildingNeighbor(PlatMap map, int x, int z) {
        int[][] neighbors = {{x - 1, z}, {x + 1, z}, {x, z - 1}, {x, z + 1}};
        for (int[] point : neighbors) {
            if (!map.inBounds(point[0], point[1])) continue;
            PlatLot lot = map.getLot(point[0], point[1]);
            if (lot != null && !(lot instanceof RoadLot) && !map.isNaturalLot(point[0], point[1])) return true;
        }
        return false;
    }

    private static boolean clearAndLevel(ServerLevel level, BlockPos center, Direction facing) {
        for (int length = -2; length <= 2; length++) {
            for (int width = -1; width <= 1; width++) {
                BlockPos at = offset(center, facing, width, length);
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) != center.getY()) return false;
                if (!replaceableDecoration(level.getBlockState(at)) || !replaceableDecoration(level.getBlockState(at.above()))) return false;
                if (!level.getFluidState(at.below()).isEmpty() || !level.getBlockState(at.below()).isSolid()) return false;
                if (level.getBlockEntity(at.below()) != null) return false;
            }
        }
        return true;
    }

    private static boolean replaceableDecoration(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.SNOW) || state.is(Blocks.VINE);
    }

    private static BlockPos offset(BlockPos center, Direction facing, int width, int length) {
        return center.relative(facing, length).relative(facing.getClockWise(), width);
    }
}
