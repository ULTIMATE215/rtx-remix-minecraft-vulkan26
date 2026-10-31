/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionCaptureAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkSlotMoveLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tracks slot moves and the neighbourhood of each submitted region snapshot. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess, BuiltChunkNeighbourhoodAccess {
    @Shadow @Final public int index;
    @Shadow public abstract long getSectionNode();
    @Shadow public abstract SectionMesh getSectionMesh();
    @Unique private int rtxremix$snapshotMask;

    @Override
    public boolean rtxremix$hasBlockEntities() {
        // Empty raster meshes can be published by workers. Read the published
        // mesh instead of maintaining a second flag that can race a slot reset.
        return !getSectionMesh().getRenderableBlockEntities().isEmpty();
    }

    @Override public int rtxremix$snapshotMask() { return rtxremix$snapshotMask; }
    @Override public void rtxremix$clearSnapshotBit(int bit) { rtxremix$snapshotMask &= ~bit; }

    @Inject(method = "reset", at = @At("HEAD"))
    private void rtxremix$clearNeighbourhood(CallbackInfo ci) {
        rtxremix$snapshotMask = 0;
    }

    @Inject(method = "setSectionNode", at = @At("RETURN"))
    private void rtxremix$recordMove(long sectionNode, CallbackInfo ci) {
        ChunkSlotMoveLog.RENDER_THREAD.record(index);
    }

    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void rtxremix$recordSnapshot(RenderSectionRegion region, CallbackInfoReturnable<?> cir) {
        RegionCaptureAccess capture = (RegionCaptureAccess) region;
        if (capture.rtxremix$sectionNode() != getSectionNode()) return;
        rtxremix$snapshotMask = capture.rtxremix$snapshotMask();
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        long node = getSectionNode();
        double dx = (SectionPos.x(node) << 4) + 8.0 - camera.x;
        double dy = (SectionPos.y(node) << 4) + 8.0 - camera.y;
        double dz = (SectionPos.z(node) << 4) + 8.0 - camera.z;
        if (NeighbourReflagFilter.isIncompleteFirstBuild(
                getSectionMesh() == CompiledSectionMesh.UNCOMPILED,
                dx * dx + dy * dy + dz * dz, rtxremix$snapshotMask)) {
            NeighbourReflagFilter.recordIncompleteFirstBuild();
        }
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void rtxremix$checkEmptySection(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        if (!mesh.hasRenderableLayers()) {
            long node = getSectionNode();
            // Remix builds intentionally have no raster layers. The consumer
            // checks the live section before treating this as a terrain removal.
            SectionLeftRenderSetQueue.enqueueEmptyCandidate(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        }
    }
}
