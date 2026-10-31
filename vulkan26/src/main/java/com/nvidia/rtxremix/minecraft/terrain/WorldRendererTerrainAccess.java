/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Terrain rebuild scheduling owned by WorldRenderer.
 */
public interface WorldRendererTerrainAccess {

    /**
     * The sections vanilla currently holds built data for, as packed
     * {@code ChunkSectionPos} longs. This is the terrain the player has seen:
     * vanilla builds a section when its occlusion walk reaches it, so the set is
     * the frustum-driven subset of the loaded area. Take it before a
     * {@code WorldRenderer.reload()}, which forgets it.
     */
    LongSet rtxremix$builtSectionPositions();

    /**
     * Accepts loaded, non-empty, lit sections for Remix capture, nearest the
     * camera first. If extraction has a pending geometry invalidation, keeps
     * the positions until the replacement section ring has been positioned.
     *
     * @return an owned subset of {@code sections}; deferred submission removes
     *         positions that cease to be resident or ready before it runs
     */
    LongSet rtxremix$queueSectionsForRemix(LongSet sections);

    /**
     * Flags a section for vanilla rebuild if its built-chunk slot still holds
     * it. A section that has left the window is skipped: slot repositioning
     * already forces a rebuild when it re-enters.
     */
    void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz);

    /**
     * Forgets that column (cx, cz) was in the region snapshots of its eight
     * neighbour columns' slots, because it unloaded or is being reloaded in
     * place. Its next load then re-flags those slots instead of being dropped
     * as already captured.
     */
    void rtxremix$onNeighbourColumnInvalidated(int cx, int cz);
}
