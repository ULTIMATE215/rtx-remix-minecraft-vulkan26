/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkStorageTorus;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Moves complete changed planes in both the render slots and 26.2's dirty-state ring. */
@Mixin(RotatingSectionStorage.class)
public abstract class RotatingSectionStorageMixin<T extends RotatingSectionStorage.Value>
        implements ChunkStorageTorus.StorageAccess {
    @Shadow @Final private RotatingSectionStorage.Node<T>[] nodes;
    @Shadow @Final private int sectionGridSizeXZ;
    @Shadow @Final private int sectionGridSizeY;
    @Shadow @Final private int minY;
    @Shadow private SectionPos centerSectionPos;
    private ChunkStorageTorus.Workspace rtxremix$workspace;

    @Inject(method = "repositionCenter", at = @At("HEAD"), cancellable = true)
    private void rtxremix$moveChangedPlanes(SectionPos center, CallbackInfoReturnable<Boolean> cir) {
        if (!RtxRemixMod.isRtxEnabled()) return;
        if (center.equals(centerSectionPos)) {
            cir.setReturnValue(false);
            return;
        }
        if (rtxremix$workspace == null) rtxremix$workspace = new ChunkStorageTorus.Workspace();
        try (ProfileScope scope = ProfileScope.begin("ChunkStorage.updateCameraPosition")) {
            int moved = ChunkStorageTorus.reposition(this, rtxremix$workspace,
                    sectionGridSizeXZ, sectionGridSizeY, sectionGridSizeXZ,
                    SectionPos.sectionToBlockCoord(minY),
                    center.x(), center.z());
            scope.captureCount(moved).scannedChunkCount(sectionGridSizeXZ * 2);
        }
        centerSectionPos = center;
        cir.setReturnValue(true);
    }

    @Override
    public int rtxremix$getOriginX(int slotIndex) {
        return SectionPos.sectionToBlockCoord(SectionPos.x(nodes[slotIndex].value().getSectionNode()));
    }

    @Override
    public int rtxremix$getOriginZ(int slotIndex) {
        return SectionPos.sectionToBlockCoord(SectionPos.z(nodes[slotIndex].value().getSectionNode()));
    }

    @Override
    public void rtxremix$setOrigin(int slotIndex, int x, int y, int z) {
        nodes[slotIndex].value().setSectionNode(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
    }
}
