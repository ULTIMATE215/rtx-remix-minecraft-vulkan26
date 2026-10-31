/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;

import java.util.concurrent.atomic.LongAdder;

/** Bounded startup diagnostics for a scene that receives no terrain captures. */
public final class TerrainCaptureDiagnostics {
    private static final LongAdder TASKS_CREATED = new LongAdder();
    private static final LongAdder TASKS_STARTED = new LongAdder();
    private static final LongAdder CAPTURES_STARTED = new LongAdder();
    private static final LongAdder CAPTURES_COMPLETED = new LongAdder();
    private static final LongAdder CAPTURES_FAILED = new LongAdder();

    // Only extraction on the render thread owns these fields. Worker counters
    // stay cumulative, so a world change cannot erase an in-flight task's result.
    private static ClientLevel world;
    private static int framesUntilSample;
    private static int samples;

    private TerrainCaptureDiagnostics() {}

    public static void taskCreated() { TASKS_CREATED.increment(); }
    public static void taskStarted() { TASKS_STARTED.increment(); }
    public static void captureStarted() { CAPTURES_STARTED.increment(); }
    public static void captureCompleted() { CAPTURES_COMPLETED.increment(); }
    public static void captureFailed() { CAPTURES_FAILED.increment(); }

    /** Release the diagnostic world's reference even when extraction stops on disconnect. */
    public static void reset() { world = null; }

    /** No per-frame allocation or section scan; at most six samples per capture session. */
    public static void afterExtraction(LevelRenderer renderer, LevelRenderState state,
                                       SectionUpdateTracker tracker) {
        Minecraft client = Minecraft.getInstance();
        if (!RtxRemixMod.isWorldActive() || client.level == null) {
            world = null;
            return;
        }
        if (world != client.level) {
            world = client.level;
            framesUntilSample = 120;
            samples = 0;
        }
        if (samples >= 6 || --framesUntilSample > 0) return;
        framesUntilSample = 600;
        int columns = RtxRemixMod.renderer.getChunkManager().getLoadedChunkCount();
        if (columns > 0) {
            if (samples > 0) {
                RtxRemixMod.LOGGER.info("Terrain capture resumed: {} columns; completed captures={}",
                        columns, CAPTURES_COMPLETED.sum());
            }
            samples = 6;
            return;
        }
        samples++;

        var visible = renderer.visibleSections();
        int uncompiled = 0, dirty = 0, missingDirtyState = 0, waitingForNeighbors = 0;
        for (int i = 0; i < visible.size(); i++) {
            var section = visible.get(i);
            boolean needsFirstBuild = section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED;
            if (needsFirstBuild) uncompiled++;
            var status = tracker == null ? null : tracker.getDirtyState(section.getSectionNode());
            if (status == null) missingDirtyState++;
            else if (status.isDirty()) dirty++;
            if (needsFirstBuild && tracker != null
                    && !tracker.hasAllNeighbors(world, section.getSectionNode())) waitingForNeighbors++;
        }
        var dispatcher = renderer.sectionRenderDispatcher();
        var area = renderer.viewArea();
        long cameraNode = SectionPos.asLong(client.gameRenderer.mainCamera().blockPosition());
        var cameraSection = area == null ? null : area.getRenderSection(cameraNode);
        boolean cameraResident = cameraSection != null && cameraSection.getSectionNode() == cameraNode;
        boolean cameraReached = cameraResident && renderer.sectionOcclusionGraph().getNode(cameraSection) != null;

        RtxRemixMod.LOGGER.warn("Terrain pipeline has no captured columns (sample {}): "
                        + "visible={}, extracted={}, uncompiled={}, dirty={}, missingDirtyState={}, waitingForNeighbors={}, "
                        + "compileQueue={}, freeBuffers={}, pendingCaptures={}, expectedChunks={}, "
                        + "cameraSection={}, cameraResident={}, cameraReached={}, cameraNeighborsReady={}; "
                        + "totals: tasksCreated={}, tasksStarted={}, capturesStarted={}, capturesCompleted={}, capturesFailed={}",
                samples, visible.size(), state.sectionUpdateRenderStates.size(), uncompiled, dirty,
                missingDirtyState, waitingForNeighbors,
                dispatcher == null ? -1 : dispatcher.getCompileQueueSize(),
                dispatcher == null ? -1 : dispatcher.getFreeBufferCount(), BlockDataQueue.pendingCount(),
                renderer.expectedChunks().size(), SectionPos.of(cameraNode), cameraResident, cameraReached,
                tracker != null && tracker.hasAllNeighbors(world, cameraNode),
                TASKS_CREATED.sum(), TASKS_STARTED.sum(), CAPTURES_STARTED.sum(),
                CAPTURES_COMPLETED.sum(), CAPTURES_FAILED.sum());
    }
}
