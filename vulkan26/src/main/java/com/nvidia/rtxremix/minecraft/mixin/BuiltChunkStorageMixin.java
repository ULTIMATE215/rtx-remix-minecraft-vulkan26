/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin implements BuiltChunkStorageSectionAccess {
    @Shadow @Final private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;

    /** A lookup must never flag a section that only shares the same rotating slot. */
    private SectionRenderDispatcher.RenderSection rtxremix$residentSlot(int cx, int sy, int cz) {
        SectionRenderDispatcher.RenderSection slot = sections.getValue(cx, sy, cz);
        return slot != null && slot.getSectionNode() == SectionPos.asLong(cx, sy, cz) ? slot : null;
    }

    private SectionUpdateTracker rtxremix$tracker() {
        Minecraft client = Minecraft.getInstance();
        if (client.levelRenderer.viewArea() != (Object) this) return null;
        return ((LevelExtractorAccessor) client.levelExtractor).rtxremix$sectionUpdateTracker();
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz) {
        SectionUpdateTracker tracker = rtxremix$tracker();
        if (tracker != null && rtxremix$residentSlot(cx, sy, cz) != null) {
            tracker.setDirty(cx, sy, cz, false);
        }
    }

    @Override
    public void rtxremix$flagLoadedSection(int cx, int sy, int cz) {
        SectionUpdateTracker tracker = rtxremix$tracker();
        if (tracker == null || !NeighbourReflagFilter.sectionInRange(sy, sections.minY(), sections.height())) {
            return;
        }
        rtxremix$scheduleRebuildIfResident(cx, sy, cz);
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(
                    cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k]);
            boolean resident = slot != null;
            var dirty = resident ? tracker.getDirtyState(slot.getSectionNode()) : null;
            boolean awaitingBuild = dirty != null && dirty.isDirty();
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    tracker.setDirty(cx + NeighbourReflagFilter.DX[k], sy, cz + NeighbourReflagFilter.DZ[k], false);
                    NeighbourReflagFilter.recordKept();
                }
                case DROP_IN_SNAPSHOT -> SkippedSectionRebuilds.recordNeighbourInSnapshot();
                case SKIP_NOT_RESIDENT, SKIP_AWAITING_BUILD -> {
                }
            }
        }
    }

    @Override
    public void rtxremix$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = sections.minY();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int row = 0; row < sections.height(); row++) {
                SectionRenderDispatcher.RenderSection slot = rtxremix$residentSlot(neighbourX, bottomSection + row, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
}
