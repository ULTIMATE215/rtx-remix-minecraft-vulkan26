/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.mixin.ChunkLightProviderAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** Render-thread control over the client light engine's published snapshots. */
public final class ClientLightSnapshots {
    private ClientLightSnapshots() {
    }

    /**
     * Publishes pending block and sky light changes before capture switches off and queues
     * raster rebuilds. Both engines keep separate immutable snapshots throughout capture.
     * Must run on the render thread; a storage with no changes is left alone.
     */
    public static void publishRealSnapshots(ClientLevel world) {
        LevelLightEngine provider = world.getLightEngine();
        for (LightLayer type : LightLayer.values()) {
            if (provider.getLayerListener(type) instanceof ChunkLightProviderAccessor access
                    && access.rtxremix$getLightStorage() instanceof LightStorageSnapshotAccess storage) {
                storage.rtxremix$publishRealSnapshot();
            }
        }
    }
}
