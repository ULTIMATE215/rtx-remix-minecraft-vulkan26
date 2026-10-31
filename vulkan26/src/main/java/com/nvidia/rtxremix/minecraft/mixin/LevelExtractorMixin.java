/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionRegionReferences;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkArrayIterator;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/** Terrain invalidation and snapshot ownership now belong to extraction in 26.2. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private SectionUpdateTracker sectionUpdateTracker;
    @Shadow public abstract void setSectionDirty(int x, int y, int z);

    @Unique private RenderRegionCache rtxremix$frameRegionCache;
    @Unique private SectionRenderDispatcher.RenderSection[] rtxremix$blockEntitySections =
            new SectionRenderDispatcher.RenderSection[32_768];
    @Unique private int rtxremix$blockEntitySectionsUsed;
    @Unique private final BuiltChunkArrayIterator rtxremix$blockEntityIterator = new BuiltChunkArrayIterator();

    @WrapMethod(method = "extract")
    private void rtxremix$extractFrame(DeltaTracker delta, Camera camera, float partialTick,
                                     Operation<Void> original) {
        try (ProfileScope scope = ProfileScope.begin("LevelExtractor.extract")) {
            original.call(delta, camera, partialTick);
        } finally {
            RenderRegionCache cache = rtxremix$frameRegionCache;
            rtxremix$frameRegionCache = null;
            if (cache != null) ((RegionBuilderRelease) cache).rtxremix$releaseSnapshots();
        }
    }

    @WrapOperation(method = "extract", at = @At(value = "NEW",
            target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache rtxremix$captureRegionCache(Operation<RenderRegionCache> original) {
        rtxremix$frameRegionCache = original.call();
        return rtxremix$frameRegionCache;
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void rtxremix$discardOldExtraction(ClientLevel level, CallbackInfo ci) {
        ((SectionRegionReferences) levelRenderState).rtxremix$releaseSectionRegions();
        Arrays.fill(rtxremix$blockEntitySections, 0, rtxremix$blockEntitySectionsUsed, null);
        rtxremix$blockEntitySectionsUsed = 0;
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled() || sectionUpdateTracker == null) return;
        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int x0 = SectionPos.blockToSectionCoord(minX - radius);
        int y0 = SectionPos.blockToSectionCoord(minY - 1);
        int z0 = SectionPos.blockToSectionCoord(minZ - radius);
        int x1 = SectionPos.blockToSectionCoord(maxX + radius);
        int y1 = SectionPos.blockToSectionCoord(maxY + 1);
        int z1 = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) setSectionDirty(x, y, z);
            }
        }
        ci.cancel();
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"), cancellable = true)
    private void rtxremix$filterLoadedColumn(int x, int y, int z, CallbackInfo ci) {
        ViewArea area = levelRenderer.viewArea();
        if (!RtxRemixMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED || area == null) return;
        ((BuiltChunkStorageSectionAccess) area).rtxremix$flagLoadedSection(x, y, z);
        ci.cancel();
    }

    @Redirect(method = "extractVisibleBlockEntities", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;iterator()Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"))
    private ObjectListIterator<SectionRenderDispatcher.RenderSection> rtxremix$blockEntitySections(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
        if (!RtxRemixMod.isRtxEnabled()) return visible.iterator();
        int count = 0;
        for (int i = 0; i < visible.size(); i++) {
            SectionRenderDispatcher.RenderSection section = visible.get(i);
            if (((ChunkBuilderBlockEntityAccess) section).rtxremix$hasBlockEntities()) {
                if (count == rtxremix$blockEntitySections.length) {
                    rtxremix$blockEntitySections = Arrays.copyOf(rtxremix$blockEntitySections, count + (count >> 1));
                }
                rtxremix$blockEntitySections[count++] = section;
            }
        }
        if (count < rtxremix$blockEntitySectionsUsed) {
            Arrays.fill(rtxremix$blockEntitySections, count, rtxremix$blockEntitySectionsUsed, null);
        }
        rtxremix$blockEntitySectionsUsed = count;
        ProfileScope.plot("Java.LevelRenderer.blockEntityChunks", count);
        ProfileScope.plot("Java.LevelRenderer.blockEntityChunkScan", visible.size());
        return rtxremix$blockEntityIterator.reset(rtxremix$blockEntitySections, count);
    }
}
