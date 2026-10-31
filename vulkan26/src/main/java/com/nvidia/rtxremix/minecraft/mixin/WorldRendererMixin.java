/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.cloud.WorldRendererCloudAccess;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionRegionReferences;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keeps vanilla's traversal and resource passes while replacing scene draws with Remix. */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin implements WorldRendererCloudAccess, WorldRendererProfilingAccess,
        WorldRendererStarAccess, WorldRendererTerrainAccess {
    @Shadow private ViewArea viewArea;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Shadow private net.minecraft.client.renderer.SkyRenderer skyRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Unique private LongSet rtxremix$recaptureSections = LongSets.emptySet();
    @Unique private ClientLevel rtxremix$recaptureWorld;
    @Unique private ProfileScope rtxremix$phaseScope;
    @Unique private int rtxremix$layerCalls;
    @Unique private int rtxremix$layerSkips;
    @Unique private int rtxremix$phaseCallsStart;
    @Unique private int rtxremix$phaseSkipsStart;
    @Unique private int rtxremix$entityCount;
    @Unique private int rtxremix$blockEntityCount;
    @Unique private int rtxremix$breakingCount;

    @Override public long rtxremix$getCloudTicks() { return levelRenderState.gameTime; }

    @Override
    public float[] rtxremix$copyStarVertices() {
        return skyRenderer == null ? new float[0] : ((WorldRendererStarAccess) skyRenderer).rtxremix$copyStarVertices();
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongSet built = new LongOpenHashSet();
        if (viewArea == null) return built;
        SectionPos center = viewArea.getCameraSectionPos();
        int distance = viewArea.getViewDistance();
        for (int z = center.z() - distance; z <= center.z() + distance; z++) {
            for (int x = center.x() - distance; x <= center.x() + distance; x++) {
                for (int y = viewArea.minSectionY(); y <= viewArea.maxSectionY(); y++) {
                    long node = SectionPos.asLong(x, y, z);
                    SectionRenderDispatcher.RenderSection section = rtxremix$residentSection(node);
                    if (section != null && section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) built.add(node);
                }
            }
        }
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet requested) {
        Minecraft client = Minecraft.getInstance();
        if (viewArea == null || sectionRenderDispatcher == null || client.level == null || requested.isEmpty()) {
            return LongSets.emptySet();
        }
        LongSet accepted = new LongOpenHashSet();
        var iterator = requested.iterator();
        while (iterator.hasNext()) {
            long node = iterator.nextLong();
            if (rtxremix$residentSection(node) != null && rtxremix$readyForRecapture(client.level, node)) {
                accepted.add(node);
            }
        }
        rtxremix$recaptureSections = accepted;
        rtxremix$recaptureWorld = client.level;
        // allChanged() replaces the dirty tracker immediately, but the section
        // ring is replaced during the next extraction. Keep positions until then.
        if (!((LevelExtractorAccessor) client.levelExtractor).rtxremix$geometryInvalidationPending()) {
            rtxremix$submitRecaptures(client.level, client.gameRenderer.mainCamera());
        }
        return accepted;
    }

    @Inject(method = "invalidateCompiledGeometry", at = @At("RETURN"))
    private void rtxremix$recaptureNewSlots(ClientLevel level, Options options, Camera camera,
                                           BlockColors colors, CallbackInfo ci) {
        rtxremix$submitRecaptures(level, camera);
    }

    @Unique
    private void rtxremix$submitRecaptures(ClientLevel level, Camera camera) {
        LongSet accepted = rtxremix$recaptureSections;
        rtxremix$recaptureSections = LongSets.emptySet();
        ClientLevel expected = rtxremix$recaptureWorld;
        rtxremix$recaptureWorld = null;
        Minecraft client = Minecraft.getInstance();
        if (accepted.isEmpty()) return;
        if (!RtxRemixMod.isRtxEnabled() || level != expected || client.level != level
                || viewArea == null || sectionRenderDispatcher == null) {
            accepted.clear();
            return;
        }
        SectionUpdateTracker tracker = ((LevelExtractorAccessor) client.levelExtractor).rtxremix$sectionUpdateTracker();
        if (tracker == null) {
            accepted.clear();
            return;
        }
        List<SectionRenderDispatcher.RenderSection> sections = new ArrayList<>(accepted.size());
        var iterator = accepted.iterator();
        while (iterator.hasNext()) {
            long node = iterator.nextLong();
            SectionRenderDispatcher.RenderSection section = rtxremix$residentSection(node);
            if (section == null || tracker.getDirtyState(node) == null || !rtxremix$readyForRecapture(level, node)) {
                iterator.remove();
            } else {
                sections.add(section);
            }
        }
        Vec3 cameraPos = camera.position();
        sections.sort(Comparator.comparingDouble(section -> section.getRenderOrigin().distToCenterSqr(cameraPos)));
        sectionRenderDispatcher.setCameraPosition(cameraPos);
        RenderRegionCache cache = new RenderRegionCache();
        try {
            for (SectionRenderDispatcher.RenderSection section : sections) {
                RenderSectionRegion region = cache.createRegion(level, section.getSectionNode());
                try {
                    section.compileAsync(region);
                    tracker.getDirtyState(section.getSectionNode()).setNotDirty();
                } finally {
                    // The task retained its own reference at construction.
                    ((RegionSnapshotRelease) region).rtxremix$release();
                }
            }
        } finally {
            ((RegionBuilderRelease) cache).rtxremix$releaseSnapshots();
        }
    }

    @Unique
    private SectionRenderDispatcher.RenderSection rtxremix$residentSection(long node) {
        if (viewArea == null) return null;
        SectionRenderDispatcher.RenderSection section = viewArea.getRenderSection(node);
        return section != null && section.getSectionNode() == node ? section : null;
    }

    @Unique
    private static boolean rtxremix$readyForRecapture(ClientLevel level, long node) {
        int x = SectionPos.x(node), y = SectionPos.y(node), z = SectionPos.z(node);
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        return chunk != null && !chunk.isYSpaceEmpty(y << 4, (y << 4) + 15)
                && level.getLightEngine().lightOnInColumn(SectionPos.asLong(x, 0, z));
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int x, int y, int z) {
        if (viewArea != null) ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$scheduleRebuildIfResident(x, y, z);
    }

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int x, int z) {
        if (viewArea != null) ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$clearNeighbourSnapshotBits(x, z);
    }

    @Inject(method = {"resetLevelRenderData", "close"}, at = @At("HEAD"))
    private void rtxremix$discardWorld(CallbackInfo ci) {
        ((SectionRegionReferences) levelRenderState).rtxremix$releaseSectionRegions();
        if (!rtxremix$recaptureSections.isEmpty()) rtxremix$recaptureSections.clear();
        rtxremix$recaptureSections = LongSets.emptySet();
        rtxremix$recaptureWorld = null;
    }

    @WrapMethod(method = "render")
    private void rtxremix$renderFrame(GraphicsResourceAllocator allocator, DeltaTracker delta,
                                    boolean blockOutline, CameraRenderState camera, Matrix4fc modelView,
                                    GpuBufferSlice fog, Vector4f clearColor, boolean renderSky,
                                    Operation<Void> original) {
        rtxremix$layerCalls = rtxremix$layerSkips = 0;
        rtxremix$entityCount = levelRenderState.entityRenderStates.size();
        rtxremix$blockEntityCount = levelRenderState.blockEntityRenderStates.size();
        rtxremix$breakingCount = levelRenderState.blockBreakingRenderStates.size();
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null) renderer.beginEntityFrame();
        try (ProfileScope scope = ProfileScope.beginJfrOnly("LevelRenderer.render")
                .plotOnClose("Java.LevelRenderer.render.ms")) {
            rtxremix$transitionPhase("preamble");
            original.call(allocator, delta, blockOutline, camera, modelView, fog, clearColor, renderSky);
        } finally {
            rtxremix$closeProfilerPhaseForFabricEvent();
            ((SectionRegionReferences) levelRenderState).rtxremix$releaseSectionRegions();
        }
    }

    @WrapOperation(method = {"render", "lambda$addMainPass$0"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private void rtxremix$profilePhase(ProfilerFiller profiler, String name, Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionPhase(name);
    }

    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
    private void rtxremix$skipTerrainDraw(ChunkSectionsToRender sections, ChunkSectionLayerGroup group,
                                         GpuSampler sampler, Operation<Void> original) {
        rtxremix$layerCalls++;
        if (RtxRemixMod.isRemixWorldRendering()) {
            rtxremix$layerSkips++;
        } else {
            original.call(sections, group, sampler);
        }
    }

    @WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void rtxremix$skipParticleDraw(ParticlesRenderState particles, SubmitNodeCollector collector,
                                          CameraRenderState camera, Operation<Void> original) {
        if (!RtxRemixMod.isRemixWorldRendering()) original.call(particles, collector, camera);
    }

    @Inject(method = "lambda$addCloudsPass$0", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipCloudDraw(CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering() && RtxRemixMod.renderer.getCloudRenderer().shouldSuppressVanillaClouds()) {
            ci.cancel();
        }
    }

    @Inject(method = "lambda$addSkyPass$0", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipSkyDraw(GpuBufferSlice fog, SkyRenderState sky, CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            // Keep the pass's persistent state; sky resource creation happened
            // in addSkyPass and must run even while Remix supplies the sky image.
            RenderSystem.setShaderFog(fog);
            ci.cancel();
        }
    }

    @WrapOperation(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;I)V"))
    private void rtxremix$captureBlockDamage(SubmitNodeCollector collector, PoseStack matrices,
                                            List<BlockStateModelPart> parts, int stage, Operation<Void> original,
                                            @Local BlockBreakingRenderState breaking) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        boolean captured = renderer != null && renderer.renderBlockDamageInPlace(breaking.blockPos(), stage,
                (captureMatrices, captureCollector) -> {
                    captureMatrices.translate(breaking.blockState().getOffset(breaking.blockPos()));
                    original.call(captureCollector, captureMatrices, parts, stage);
                });
        if (!captured) original.call(collector, matrices, parts, stage);
    }

    @WrapMethod(method = "compileSections")
    private void rtxremix$compileSections(CameraRenderState camera, Operation<Void> original) {
        int waiting = sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getCompileQueueSize();
        ProfileScope.plot("Java.Chunk.toBatchCount", waiting);
        try (ProfileScope scope = ProfileScope.begin("LevelRenderer.updateChunks").dirtyLeaves(waiting)) {
            original.call(camera);
        } finally {
            ((SectionRegionReferences) levelRenderState).rtxremix$releaseSectionRegions();
        }
    }

    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"))
    private void rtxremix$profileSyncCompile(SectionRenderDispatcher.RenderSection section,
                                            RenderSectionRegion region, Operation<Void> original) {
        try (ProfileScope scope = ProfileScope.begin("LevelRenderer.updateChunks.build_near_sync")) {
            original.call(section, region);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void rtxremix$profileUpload(SectionRenderDispatcher dispatcher, Operation<Void> original) {
        try (ProfileScope scope = ProfileScope.begin("LevelRenderer.updateChunks.upload")) {
            original.call(dispatcher);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void rtxremix$profileTraversal(SectionOcclusionGraph graph, CameraRenderState camera,
                                          int fov, ChunkLoadingRenderState loading, Operation<Void> original) {
        try (ProfileScope scope = ProfileScope.beginJfrOnly("LevelRenderer.setupTerrain")
                .plotOnClose("Java.LevelRenderer.setupTerrain.ms")) {
            original.call(graph, camera, fov, loading);
        }
    }

    @Unique
    private void rtxremix$transitionPhase(String name) {
        rtxremix$closeProfilerPhaseForFabricEvent();
        if (!ProfileScope.isDeepProfilingEnabled()) return;
        String phase = "LevelRenderer.phase." + name.replace(' ', '_');
        rtxremix$phaseCallsStart = rtxremix$layerCalls;
        rtxremix$phaseSkipsStart = rtxremix$layerSkips;
        rtxremix$phaseScope = ProfileScope.begin(phase).plotOnClose("Java." + phase + ".ms");
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        ProfileScope scope = rtxremix$phaseScope;
        if (scope == null) return;
        rtxremix$phaseScope = null;
        scope.visibleChunkCount(visibleSections.size()).captureCount(visibleSections.size());
        scope.entityCount(rtxremix$entityCount).blockEntityCount(rtxremix$blockEntityCount);
        scope.blockBreakingCount(rtxremix$breakingCount);
        scope.renderLayerCalls(rtxremix$layerCalls - rtxremix$phaseCallsStart);
        scope.renderLayerSkips(rtxremix$layerSkips - rtxremix$phaseSkipsStart);
        scope.close();
    }
}
