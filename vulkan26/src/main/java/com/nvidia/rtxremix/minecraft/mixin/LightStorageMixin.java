/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshotPolicy;
import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshotPolicy.Decision;
import com.nvidia.rtxremix.minecraft.terrain.LightStorageSnapshotAccess;
import com.nvidia.rtxremix.minecraft.terrain.ServerLightSnapshot;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes immutable delta layers for both client and server light readers. Clearing
 * changedSections after publication preserves vanilla's copy-on-write of each DataLayer.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LightStorageMixin<M extends DataLayerStorageMap<M>> implements LightStorageSnapshotAccess {
    @Shadow @Final protected LightChunkGetter chunkSource;
    @Shadow @Final protected M updatingSectionData;
    @Shadow protected volatile M visibleSectionData;
    @Shadow @Final protected LongSet changedSections;
    @Shadow @Final protected LongSet sectionsAffectedByLightUpdates;
    @Shadow @Final private LightLayer layer;

    @Unique private ClientLightSnapshotPolicy rtxremix$snapshotPolicy;
    @Unique private ServerLightSnapshot rtxremix$layeredSnapshot;

    @Inject(method = "swapSectionMap", at = @At("HEAD"), cancellable = true)
    private void rtxremix$publishSnapshot(CallbackInfo ci) {
        rtxremix$classify();
        Decision decision = rtxremix$snapshotPolicy.onPublish(
                RtxRemixMod.isRtxEnabled(), VanillaSectionBuilds.anyInFlight());
        if (decision == Decision.VANILLA) {
            return;
        }
        rtxremix$publishChangedSections();
        if (decision == Decision.LAYERED_SKIP_NOTIFICATIONS) {
            SkippedSectionRebuilds.recordLightOnly(sectionsAffectedByLightUpdates.size());
        } else if (!sectionsAffectedByLightUpdates.isEmpty()) {
            for (LongIterator sections = sectionsAffectedByLightUpdates.iterator(); sections.hasNext(); ) {
                chunkSource.onLightUpdate(layer, SectionPos.of(sections.nextLong()));
            }
        }
        sectionsAffectedByLightUpdates.clear();
        ci.cancel();
    }

    @Override
    public void rtxremix$publishRealSnapshot() {
        rtxremix$classify();
        // Use the same publisher on a capture toggle, so its delta history includes
        // every change before changedSections is cleared. Notifications remain queued.
        rtxremix$publishChangedSections();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void rtxremix$publishChangedSections() {
        if (changedSections.isEmpty()) {
            return;
        }
        M snapshot;
        if (rtxremix$layeredSnapshot == null) {
            snapshot = updatingSectionData.copy();
        } else if (updatingSectionData instanceof SkyLightSectionStorage.SkyDataLayerStorageMap sky) {
            ServerLightSnapshot.Published published = rtxremix$layeredSnapshot.publish(
                    updatingSectionData.map, sky.topSections, changedSections);
            snapshot = (M) new SkyLightSectionStorage.SkyDataLayerStorageMap(
                    published.sections(), published.columns(), sky.currentLowestY);
        } else {
            ServerLightSnapshot.Published published = rtxremix$layeredSnapshot.publish(
                    updatingSectionData.map, null, changedSections);
            snapshot = (M) new BlockLightSectionStorage.BlockDataLayerStorageMap(published.sections());
        }
        snapshot.disableCache();
        visibleSectionData = snapshot;
        changedSections.clear();
    }

    @Unique
    private void rtxremix$classify() {
        if (rtxremix$snapshotPolicy != null) {
            return;
        }
        boolean supported = updatingSectionData instanceof SkyLightSectionStorage.SkyDataLayerStorageMap
                || updatingSectionData instanceof BlockLightSectionStorage.BlockDataLayerStorageMap;
        boolean layered = supported && ClientLightSnapshotPolicy.LAYERED_ALLOWED;
        rtxremix$snapshotPolicy = new ClientLightSnapshotPolicy(
                chunkSource.getLevel() instanceof ClientLevel, layered);
        if (layered) {
            rtxremix$layeredSnapshot = new ServerLightSnapshot();
        }
    }
}
