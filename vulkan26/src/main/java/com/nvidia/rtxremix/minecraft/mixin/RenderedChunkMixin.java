/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionSnapshotAccess;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/** A pooled section copy shared by the cache and the regions extracted from it. */
@Mixin(SectionCopy.class)
public abstract class RenderedChunkMixin implements SectionSnapshotAccess {
    @Shadow @Final @Mutable private PalettedContainer<BlockState> section;
    @Unique private final AtomicInteger rtxremix$references = new AtomicInteger();
    @Unique private boolean rtxremix$columnLoaded;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$recordColumn(LevelChunk chunk, int sectionIndex, CallbackInfo ci) {
        rtxremix$columnLoaded = !(chunk instanceof EmptyLevelChunk);
    }

    @Override
    public boolean rtxremix$columnLoaded() {
        return rtxremix$columnLoaded;
    }

    @Override
    public void rtxremix$retain() {
        rtxremix$references.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        int remaining = rtxremix$references.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("Section snapshot released more than once");
        }
        if (remaining != 0 || section == null) return;
        if (section.data.storage() instanceof SimpleBitStorage packed) {
            RenderRegionStoragePool.release(packed.getRaw());
        }
        // All regions that could read this copy have now cleared their section arrays.
        section = null;
    }
}
