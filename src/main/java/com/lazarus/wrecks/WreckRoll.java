package com.lazarus.wrecks;

final class WreckRoll {
    private WreckRoll() {}

    static long seed(long worldSeed, int chunkX, int chunkZ) {
        long value = worldSeed ^ (0x9E3779B97F4A7C15L * chunkX) ^ (0xC2B2AE3D27D4EB4FL * chunkZ);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    static boolean selected(long seed, double chance) {
        return ((seed >>> 11) * 0x1.0p-53) < chance;
    }
}
