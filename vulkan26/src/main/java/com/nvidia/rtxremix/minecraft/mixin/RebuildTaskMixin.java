/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionCaptureAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBuildCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Releases cancelled jobs without returning storage that a running worker still reads. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class RebuildTaskMixin {
    @Shadow @Final private RenderSectionRegion region;
    // 0: queued, 1: running, 2: released. Only the winner releases the task's reference.
    @Unique private final AtomicInteger rtxremix$regionUse = new AtomicInteger();
    @Unique private final AtomicBoolean rtxremix$cancelled = new AtomicBoolean();
    @Unique private BlockDataQueue.CaptureToken rtxremix$captureToken;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$retainRegion(SectionRenderDispatcher.RenderSection section,
                                     RenderSectionRegion snapshot, boolean recompile, CallbackInfo ci) {
        ((RegionSnapshotRelease) region).rtxremix$retain();
        BlockDataQueue.CaptureToken capture = ((RegionCaptureAccess) region).rtxremix$captureToken();
        rtxremix$captureToken = new BlockDataQueue.CaptureToken(
                capture.queueGeneration(), capture.chunkToken(), rtxremix$cancelled);
    }

    @WrapMethod(method = "doTask")
    private SectionTaskResult rtxremix$consumeRegion(SectionBufferBuilderPack buffers,
                                                    Operation<SectionTaskResult> original) {
        if (!rtxremix$regionUse.compareAndSet(0, 1)) return SectionTaskResult.CANCELLED;
        boolean captureBegun = false;
        try {
            SectionBuildCapture.begin(rtxremix$captureToken);
            captureBegun = true;
            return original.call(buffers);
        } finally {
            if (captureBegun) SectionBuildCapture.end();
            rtxremix$regionUse.set(2);
            ((RegionSnapshotRelease) region).rtxremix$release();
        }
    }

    @Inject(method = "cancel", at = @At("HEAD"))
    private void rtxremix$cancelRegion(CallbackInfo ci) {
        // Reset cancels its previous task before replacing the slot's mesh or
        // coordinates. Serialize that cancellation with the empty-mesh publish.
        synchronized (this) {
            rtxremix$cancelled.set(true);
        }
        BlockDataQueue.captureCancelled();
        if (rtxremix$regionUse.compareAndSet(0, 2)) {
            ((RegionSnapshotRelease) region).rtxremix$release();
        }
    }

    @WrapOperation(method = "doTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh rtxremix$publishEmptyRasterMesh(SectionRenderDispatcher.RenderSection section,
                                                      SectionMesh mesh, Operation<SectionMesh> original) {
        // With Remix capture every build takes vanilla's empty-layer fast path,
        // which otherwise publishes even after its slot has been cancelled.
        synchronized (this) {
            if (rtxremix$cancelled.get()
                    || section.getSectionNode() != ((RegionCaptureAccess) region).rtxremix$sectionNode()) {
                return mesh; // Vanilla releases the returned mesh under its copy lock.
            }
            return original.call(section, mesh);
        }
    }
}
