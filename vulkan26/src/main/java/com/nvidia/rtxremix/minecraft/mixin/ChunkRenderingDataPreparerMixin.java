/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkFrustumIndex;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkFrustumIndexBuild;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkSlotMoveLog;
import com.nvidia.rtxremix.minecraft.terrain.visibility.FarChunkProbe;
import com.nvidia.rtxremix.minecraft.terrain.visibility.HierarchicalFrustumCuller;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ReachedChunkSet;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.TimeSlicedWorkQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.google.common.collect.Queues;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphState;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Keeps 26.2's loaded-column and empty-section traversal while reusing graph nodes,
 * slicing incremental propagation, and publishing the parent's coarse frustum index.
 * The vanilla octree remains populated for its other readers, including debug views.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class ChunkRenderingDataPreparerMixin {
    @Shadow @Final private AtomicReference<GraphState> currentGraph;
    @Shadow @Final private static Direction[] DIRECTIONS;
    @Shadow @Final private static double CEILED_SECTION_DIAGONAL;
    @Shadow @Final private static int MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE;
    @Shadow private ViewArea viewArea;
    @Shadow protected abstract RenderSection getRelativeFrom(long cameraSection, RenderSection section, Direction direction);

    private static final boolean rtxremix$VANILLA_WALK = Boolean.getBoolean("rtxremix.vanillaVisibilityWalk");
    private static final boolean rtxremix$LOG_VISIBLE_SET = Boolean.getBoolean("rtxremix.logVisibleSet");
    private static long rtxremix$lastVisibleSetSignature;
    private static final ExecutorService rtxremix$visibilityExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RTX Remix chunk visibility");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return thread;
    });
    private static final ThreadLocal<BuiltChunkFrustumIndexBuild> rtxremix$frustumIndexBuild = new ThreadLocal<>();
    private volatile BuiltChunkFrustumIndex rtxremix$frustumIndex;
    private final ConcurrentLinkedQueue<HierarchicalFrustumCuller<RenderSection>> rtxremix$cullerPool =
            new ConcurrentLinkedQueue<>();
    private Frustum rtxremix$cullingFrustum;

    private static final long rtxremix$PROPAGATION_BUDGET_NANOS = 1_500_000L;
    private static final int rtxremix$PROPAGATION_MINIMUM_POLLS = 64;
    private final TimeSlicedWorkQueue<Node> rtxremix$propagationQueue = new TimeSlicedWorkQueue<>(
            rtxremix$PROPAGATION_BUDGET_NANOS, rtxremix$PROPAGATION_MINIMUM_POLLS, System::nanoTime);
    private BlockingQueue<?> rtxremix$propagationSeeds;

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$storageScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$frustumScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$prepareScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$graphScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$seedScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$walkScope = new ThreadLocal<>();

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void rtxremix$storageHead(ViewArea storage, CallbackInfo ci) {
        rtxremix$open(rtxremix$storageScope, "ChunkVisibility.setStorage");
    }

    // Vanilla has joined the old full walk before it replaces viewArea. Reusing its
    // buffers any earlier could reset nodes that the old worker is still traversing.
    @Inject(method = "waitAndReset", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;viewArea:Lnet/minecraft/client/renderer/ViewArea;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void rtxremix$configureBuffers(ViewArea storage, CallbackInfo ci) {
        VisibilityGraphBuffers.configure(storage == null ? 0 : storage.size());
    }

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void rtxremix$storageReturn(ViewArea storage, CallbackInfo ci) {
        BuiltChunkFrustumIndex retired = rtxremix$frustumIndex;
        rtxremix$frustumIndex = null;
        rtxremix$recycle(retired);
        rtxremix$cullerPool.clear();
        rtxremix$frustumIndexBuild.remove();
        rtxremix$propagationQueue.clear();
        rtxremix$propagationSeeds = null;
        rtxremix$close(rtxremix$storageScope);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"), cancellable = true)
    private void rtxremix$frustumHead(Frustum frustum, List<RenderSection> visible,
                                    List<RenderSection> nearby, CallbackInfo ci) {
        rtxremix$open(rtxremix$frustumScope, "ChunkVisibility.applyFrustum", visible.size());
        if (RtxRemixMod.isRtxEnabled() && rtxremix$applyFrustumIndex(frustum, visible, nearby)) {
            rtxremix$captureCount(rtxremix$frustumScope, visible.size());
            rtxremix$close(rtxremix$frustumScope);
            ci.cancel();
        }
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void rtxremix$frustumReturn(Frustum frustum, List<RenderSection> visible,
                                      List<RenderSection> nearby, CallbackInfo ci) {
        rtxremix$captureCount(rtxremix$frustumScope, visible.size());
        rtxremix$close(rtxremix$frustumScope);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$prepareHead(CameraRenderState camera, int distance,
                                     ChunkLoadingRenderState loading, CallbackInfo ci) {
        rtxremix$open(rtxremix$prepareScope, "ChunkVisibility.prepare");
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void rtxremix$prepareReturn(CameraRenderState camera, int distance,
                                       ChunkLoadingRenderState loading, CallbackInfo ci) {
        rtxremix$close(rtxremix$prepareScope);
    }

    @Redirect(method = "scheduleFullUpdate", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> rtxremix$scheduleVisibility(Runnable task, Executor executor) {
        return CompletableFuture.runAsync(task,
                RtxRemixMod.isRtxEnabled() ? rtxremix$visibilityExecutor : executor);
    }

    @Inject(method = "lambda$scheduleFullUpdate$0", at = @At("HEAD"))
    private void rtxremix$beginFrustumIndex(CameraRenderState camera, LongOpenHashSet empty,
                                          LongOpenHashSet loaded, CallbackInfo ci) {
        VisibilityGraphBuffers.rotate();
        if (RtxRemixMod.isRtxEnabled()) {
            rtxremix$frustumIndexBuild.set(new BuiltChunkFrustumIndexBuild(
                    ChunkSlotMoveLog.RENDER_THREAD.sequence(), rtxremix$cullerPool.poll()));
        } else {
            rtxremix$frustumIndexBuild.remove();
        }
    }

    @Redirect(method = "lambda$scheduleFullUpdate$0", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V"))
    private void rtxremix$publishFrustumIndex(AtomicReference<Object> reference, Object state) {
        BuiltChunkFrustumIndexBuild build = rtxremix$frustumIndexBuild.get();
        rtxremix$frustumIndexBuild.remove();
        BuiltChunkFrustumIndex superseded = rtxremix$frustumIndex;
        // The build carries its starting move sequence; publication must not skip
        // section moves that happened while the worker was filling the index.
        rtxremix$frustumIndex = build == null ? null : build.publish(state);
        reference.set(state);
        rtxremix$recycle(superseded);
    }

    @Redirect(method = "initializeQueueForFullUpdate", at = @At(value = "NEW",
            target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/core/Direction;I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$Node;"))
    private Node rtxremix$seedNode(RenderSection section, Direction direction, int step) {
        return VisibilityGraphBuffers.current().infoPool.acquire(section, direction, step);
    }

    @Redirect(method = "runUpdates", at = @At(value = "NEW",
            target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/core/Direction;I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$Node;"))
    private Node rtxremix$walkNode(RenderSection section, Direction direction, int step,
                                  GraphStorage storage, Vec3 camera, Queue<Node> queue, boolean culling,
                                  Consumer<RenderSection> consumer, LongOpenHashSet empty, LongOpenHashSet loaded) {
        return ((RenderableChunksBuffers) storage).rtxremix$buffers().infoPool.acquire(section, direction, step);
    }

    @Redirect(method = "runUpdates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Octree;add(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)Z"))
    private boolean rtxremix$indexVanillaVisible(Octree tree, RenderSection section,
                                               GraphStorage storage, Vec3 camera, Queue<Node> queue,
                                               boolean culling, Consumer<RenderSection> consumer,
                                               LongOpenHashSet empty, LongOpenHashSet loaded) {
        if (!tree.add(section)) return false;
        rtxremix$recordVisible(storage, storage.sectionToNodeMap.get(section));
        return true;
    }

    private void rtxremix$recordVisible(GraphStorage storage, Node node) {
        ReachedChunkSet chunks = ((RenderableChunksBuffers) storage).rtxremix$buffers().chunks;
        chunks.add(node);
        BuiltChunkFrustumIndexBuild build = rtxremix$frustumIndexBuild.get();
        if (build != null) {
            build.add(chunks, node.section);
            return;
        }
        BuiltChunkFrustumIndex index = rtxremix$frustumIndex;
        if (index != null && index.chunks() == chunks && index.tryAcquire()) {
            try { index.add(node.section); }
            finally { rtxremix$releaseIndex(index); }
        }
    }

    @Inject(method = "runPartialUpdate", at = @At("HEAD"))
    private void rtxremix$graphHead(CameraRenderState camera, LongSet chunks, CallbackInfo ci) {
        rtxremix$open(rtxremix$graphScope, "ChunkVisibility.rebuildGraph");
    }

    @Inject(method = "runPartialUpdate", at = @At("RETURN"))
    private void rtxremix$graphReturn(CameraRenderState camera, LongSet chunks, CallbackInfo ci) {
        rtxremix$budgetHit(rtxremix$graphScope, rtxremix$propagationQueue.budgetHit());
        rtxremix$close(rtxremix$graphScope);
    }

    @Redirect(method = "runPartialUpdate", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/BlockingQueue;isEmpty()Z", ordinal = 0))
    private boolean rtxremix$noPropagationWork(BlockingQueue<?> seeds) {
        if (!RtxRemixMod.isRtxEnabled()) return seeds.isEmpty();
        if (rtxremix$propagationSeeds != seeds) {
            rtxremix$propagationQueue.clear();
            rtxremix$propagationSeeds = seeds;
        }
        return seeds.isEmpty() && !rtxremix$propagationQueue.hasPendingWork();
    }

    @Redirect(method = "runPartialUpdate", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/Queues;newArrayDeque()Ljava/util/ArrayDeque;"))
    private ArrayDeque<Node> rtxremix$propagationWorkQueue() {
        if (!RtxRemixMod.isRtxEnabled()) {
            rtxremix$propagationQueue.clear();
            return Queues.newArrayDeque();
        }
        rtxremix$propagationQueue.beginSlice();
        return rtxremix$propagationQueue;
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("HEAD"))
    private void rtxremix$seedHead(BlockPos camera, Queue<Node> queue, CallbackInfo ci) {
        rtxremix$open(rtxremix$seedScope, "ChunkVisibility.seedQueue", queue.size());
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("RETURN"))
    private void rtxremix$seedReturn(BlockPos camera, Queue<Node> queue, CallbackInfo ci) {
        rtxremix$close(rtxremix$seedScope);
    }

    @Inject(method = "runUpdates", at = @At("HEAD"), cancellable = true)
    private void rtxremix$walkVisibleGraph(GraphStorage storage, Vec3 camera, Queue<Node> queue,
                                          boolean culling, Consumer<RenderSection> consumer,
                                          LongOpenHashSet empty, LongOpenHashSet loaded, CallbackInfo ci) {
        rtxremix$open(rtxremix$walkScope, "ChunkVisibility.walkVisibleGraph", queue.size());
        if (!RtxRemixMod.isRtxEnabled() || rtxremix$VANILLA_WALK) return;
        try { rtxremix$walk(storage, camera, queue, culling, consumer, empty, loaded); }
        finally { rtxremix$close(rtxremix$walkScope); }
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("port", storage);
        ci.cancel();
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void rtxremix$walkReturn(GraphStorage storage, Vec3 camera, Queue<Node> queue,
                                     boolean culling, Consumer<RenderSection> consumer,
                                     LongOpenHashSet empty, LongOpenHashSet loaded, CallbackInfo ci) {
        rtxremix$close(rtxremix$walkScope);
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("vanilla", storage);
    }

    private static void rtxremix$logVisibleSet(String walk, GraphStorage storage) {
        ReachedChunkSet chunks = ((RenderableChunksBuffers) storage).rtxremix$buffers().chunks;
        long hash = 0;
        for (Node node : chunks) {
            hash += (node.section.getSectionNode() * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
        }
        long signature = hash ^ ((long) chunks.size() << 48);
        if (signature != rtxremix$lastVisibleSetSignature) {
            rtxremix$lastVisibleSetSignature = signature;
            RtxRemixMod.LOGGER.info("Visible set ({} walk): {} sections, hash {}",
                    walk, chunks.size(), Long.toHexString(hash));
        }
    }

    /** Same order and decisions as SectionOcclusionGraph.runUpdates in 26.2. */
    private void rtxremix$walk(GraphStorage storage, Vec3 camera, Queue<Node> queue,
                              boolean culling, Consumer<RenderSection> consumer,
                              LongOpenHashSet empty, LongOpenHashSet loaded) {
        var buffers = ((RenderableChunksBuffers) storage).rtxremix$buffers();
        var infos = storage.sectionToNodeMap;
        FarChunkProbe.GraphLookup graph = buffers.graphLookup(viewArea);
        int cx = SectionPos.blockToSectionCoord(camera.x);
        int cy = SectionPos.blockToSectionCoord(camera.y);
        int cz = SectionPos.blockToSectionCoord(camera.z);
        long cameraSection = SectionPos.asLong(cx, cy, cz);
        int centerX = SectionPos.sectionToBlockCoord(cx) + 8;
        int centerY = SectionPos.sectionToBlockCoord(cy) + 8;
        int centerZ = SectionPos.sectionToBlockCoord(cz) + 8;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            RenderSection section = node.section;
            long sectionPos = section.getSectionNode();
            long chunkPos = ChunkPos.fromSectionNode(sectionPos);
            if (!loaded.contains(chunkPos)) {
                LongList waiting = storage.sectionsWaitingForChunkLoads.get(chunkPos);
                if (waiting == null) {
                    waiting = new LongArrayList();
                    storage.sectionsWaitingForChunkLoads.put(chunkPos, waiting);
                }
                waiting.add(sectionPos);
                continue;
            }
            if (!empty.contains(section.getSectionNode())) {
                if (storage.sectionTree.add(section)) {
                    rtxremix$recordVisible(storage, node);
                    consumer.accept(section);
                }
            } else {
                section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY);
            }
            int sx = SectionPos.x(sectionPos);
            int sy = SectionPos.y(sectionPos);
            int sz = SectionPos.z(sectionPos);
            boolean far = Math.abs(sx - cx) > MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE
                    || Math.abs(sy - cy) > MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE
                    || Math.abs(sz - cz) > MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE;
            for (Direction direction : DIRECTIONS) {
                RenderSection neighbour = getRelativeFrom(cameraSection, section, direction);
                if (neighbour == null || (culling && node.hasDirection(direction.getOpposite()))) continue;
                if (culling && node.hasSourceDirections()) {
                    SectionMesh mesh = section.getSectionMesh();
                    boolean through = false;
                    for (int i = 0; i < DIRECTIONS.length; i++) {
                        if (node.hasSourceDirection(i)
                                && mesh.facesCanSeeEachother(DIRECTIONS[i].getOpposite(), direction)) {
                            through = true;
                            break;
                        }
                    }
                    if (!through) continue;
                }
                // 26.2 starts the probe at the current section's face, rather than
                // the neighbour's origin. Its JOML normalization also differs from 1.21.1.
                if (culling && far && !FarChunkProbe.reachesCamera(
                        SectionPos.sectionToBlockCoord(sx), SectionPos.sectionToBlockCoord(sy),
                        SectionPos.sectionToBlockCoord(sz), direction.getAxis(), centerX, centerY, centerZ,
                        camera.x, camera.y, camera.z, CEILED_SECTION_DIAGONAL,
                        viewArea.maxY(), viewArea.minY(), graph)) continue;
                Node neighbourNode = infos.get(neighbour);
                if (neighbourNode != null) {
                    neighbourNode.addSourceDirection(direction);
                    continue;
                }
                Node discovered = buffers.infoPool.acquire(neighbour, direction, node.step + 1);
                discovered.setDirections(node.directions, direction);
                queue.add(discovered);
                infos.put(neighbour, discovered);
            }
        }
    }

    private void rtxremix$recycle(BuiltChunkFrustumIndex superseded) {
        if (superseded == null) {
            return;
        }
        HierarchicalFrustumCuller<RenderSection> culler = superseded.tryRetire();
        if (culler != null && rtxremix$cullerPool.size() < 2) {
            rtxremix$cullerPool.offer(culler);
        }
    }

    /** Ends a render-thread use; recycles the index if a publish superseded it meanwhile. */
    private void rtxremix$releaseIndex(BuiltChunkFrustumIndex index) {
        index.release();
        if (rtxremix$frustumIndex != index) {
            rtxremix$recycle(index);
        }
    }

    private static int rtxremix$sizeOf(List<?> values) {
        return values == null ? -1 : values.size();
    }

    private static int rtxremix$sizeOf(Queue<?> values) {
        return values == null ? -1 : values.size();
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName) {
        rtxremix$open(holder, phaseName, -1);
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (captureCount >= 0) {
            scope.captureCount(captureCount);
        }
        stack.push(scope);
    }

    private static void rtxremix$captureCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled() || captureCount < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().captureCount(captureCount);
        }
    }

    private static void rtxremix$scannedChunkCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int scanned) {
        if (!ProfileScope.isDeepProfilingEnabled() || scanned < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().scannedChunkCount(scanned);
        }
    }

    private static void rtxremix$budgetHit(ThreadLocal<ArrayDeque<ProfileScope>> holder, boolean budgetHit) {
        if (!ProfileScope.isDeepProfilingEnabled() || !budgetHit) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().budgetHit(true);
        }
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }

    private boolean rtxremix$applyFrustumIndex(Frustum frustum, List<RenderSection> visible,
                                              List<RenderSection> nearby) {
        BuiltChunkFrustumIndex index = rtxremix$frustumIndex;
        if (index == null || index.state() != currentGraph.get() || !index.tryAcquire()) return false;
        try {
            index.reconcile(ChunkSlotMoveLog.RENDER_THREAD);
            rtxremix$scannedChunkCount(rtxremix$frustumScope, index.size());
            if (rtxremix$cullingFrustum == null) rtxremix$cullingFrustum = new Frustum(frustum);
            else rtxremix$cullingFrustum.set(frustum);
            Frustum cullingFrustum = rtxremix$cullingFrustum.offsetToFullyIncludeCameraCube(8);
            FrustumAccessor accessor = (FrustumAccessor) cullingFrustum;
            FrustumIntersection intersection = accessor.rtxremix$getFrustumIntersection();
            double x = cullingFrustum.getCamX();
            double y = cullingFrustum.getCamY();
            double z = cullingFrustum.getCamZ();
            // The octree captures this center when the graph is constructed. Using
            // this frame's camera section would disagree with its nearby-section list.
            BlockPos center = ((GraphState) index.state()).storage().sectionTree.cameraSectionCenter;
            index.forEachVisible((minX, minY, minZ, maxX, maxY, maxZ) -> {
                int classification = intersection.intersectAab((float) (minX - x), (float) (minY - y),
                        (float) (minZ - z), (float) (maxX - x), (float) (maxY - y), (float) (maxZ - z));
                if (classification == FrustumIntersection.INSIDE) return HierarchicalFrustumCuller.INSIDE;
                if (classification == FrustumIntersection.INTERSECT) return HierarchicalFrustumCuller.INTERSECT;
                return HierarchicalFrustumCuller.OUTSIDE;
            }, section -> cullingFrustum.isVisible(section.getBoundingBox()), section -> {
                visible.add(section);
                AABB box = section.getBoundingBox();
                if (center.getX() > box.minX - 32 && center.getX() < box.maxX + 32
                        && center.getY() > box.minY - 32 && center.getY() < box.maxY + 32
                        && center.getZ() > box.minZ - 32 && center.getZ() < box.maxZ + 32) nearby.add(section);
            });
            return true;
        } finally {
            rtxremix$releaseIndex(index);
        }
    }
}
