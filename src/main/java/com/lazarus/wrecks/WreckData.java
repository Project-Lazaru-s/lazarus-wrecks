package com.lazarus.wrecks;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

final class WreckData extends SavedData {
    private static final String NAME = "lazarus_wrecks";
    private final Set<Long> chunks = new HashSet<>();

    static WreckData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WreckData::load, WreckData::new, NAME);
    }

    private static WreckData load(CompoundTag tag) {
        WreckData data = new WreckData();
        for (long chunk : tag.getLongArray("placedChunks")) data.chunks.add(chunk);
        return data;
    }

    boolean contains(ChunkPos pos) {
        return chunks.contains(pos.toLong());
    }

    void add(ChunkPos pos) {
        if (chunks.add(pos.toLong())) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("placedChunks", chunks.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
}
