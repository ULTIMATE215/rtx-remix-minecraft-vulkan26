/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionCaptureAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionSnapshotAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the extraction frame and its compile task's references separate. */
@Mixin(RenderSectionRegion.class)
public abstract class ChunkRendererRegionMixin implements RegionCaptureAccess {
    @Shadow @Final private SectionCopy[] sections;
    @Unique private final AtomicInteger rtxremix$references = new AtomicInteger(1);
    @Unique private long rtxremix$sectionNode;
    @Unique private int rtxremix$snapshotMask;
    @Unique private BlockDataQueue.CaptureToken rtxremix$captureToken;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$retainSnapshots(ClientLevel level, int minX, int minY, int minZ,
                                          SectionCopy[] copies, CallbackInfo ci) {
        rtxremix$sectionNode = SectionPos.asLong(minX + 1, minY + 1, minZ + 1);
        rtxremix$captureToken = BlockDataQueue.beginCapture(minX + 1, minZ + 1);
        for (SectionCopy snapshot : sections) {
            ((RegionSnapshotRelease) snapshot).rtxremix$retain();
        }
        // Use the copies' source columns, never the live world at compile time.
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int index = ((NeighbourReflagFilter.DZ[k] + 1) * 3 + 1) * 3
                    + NeighbourReflagFilter.DX[k] + 1;
            if (((SectionSnapshotAccess) sections[index]).rtxremix$columnLoaded()) {
                rtxremix$snapshotMask |= NeighbourReflagFilter.bit(k);
            }
        }
    }

    @Override public long rtxremix$sectionNode() { return rtxremix$sectionNode; }
    @Override public int rtxremix$snapshotMask() { return rtxremix$snapshotMask; }
    @Override public BlockDataQueue.CaptureToken rtxremix$captureToken() { return rtxremix$captureToken; }

    @Override
    public void rtxremix$retain() {
        if (rtxremix$references.incrementAndGet() <= 1) {
            throw new IllegalStateException("Retaining a released render region");
        }
    }

    @Override
    public void rtxremix$release() {
        int remaining = rtxremix$references.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("Render region released more than once");
        }
        if (remaining != 0) return;
        for (int i = 0; i < sections.length; i++) {
            SectionCopy snapshot = sections[i];
            sections[i] = null;
            ((RegionSnapshotRelease) snapshot).rtxremix$release();
        }
    }
}
