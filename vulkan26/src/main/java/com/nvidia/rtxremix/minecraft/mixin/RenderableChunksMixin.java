/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reuses 26.2's section-to-node map, unloaded-column wait lists, and the parent's
 * reached-section index in alternating graph states. Vanilla still owns the octree.
 */
@Mixin(GraphStorage.class)
public abstract class RenderableChunksMixin implements RenderableChunksBuffers {

    private VisibilityGraphBuffers.Buffers rtxremix$buffers;

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap;"))
    private SectionToNodeMap rtxremix$reusedInfoList(int chunkCount) {
        rtxremix$buffers = VisibilityGraphBuffers.current();
        return rtxremix$buffers.infos;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private Long2ObjectOpenHashMap<LongList> rtxremix$reusedUnbuiltNeighbours() {
        return rtxremix$buffers.unbuiltNeighbours;
    }

    @Override
    public VisibilityGraphBuffers.Buffers rtxremix$buffers() {
        return rtxremix$buffers;
    }
}
