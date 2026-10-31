/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionCaptureAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBuildCapture;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Captures block and fluid geometry inside vanilla's section loop. */
@Mixin(SectionCompiler.class)
public abstract class SectionBuilderMixin {
    @Shadow @Final private BlockStateModelSet blockModelSet;

    // A toggle must not split one build between raster and Remix geometry.
    @Unique private static final ThreadLocal<Boolean> rtxremix$captureForBuild =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @WrapMethod(method = "compile")
    private SectionCompiler.Results rtxremix$captureSection(
            SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting,
            SectionBufferBuilderPack buffers, Operation<SectionCompiler.Results> original) {
        boolean capturing = VanillaSectionBuilds.beginBuild(RtxRemixMod::isRtxEnabled);
        rtxremix$captureForBuild.set(capturing);
        BlockQuadEmitter emitter = null;
        try {
            if (!capturing) {
                return original.call(sectionPos, region, sorting, buffers);
            }
            // Extraction precedes compilation in 26.2. Keep the generation of the
            // snapshot, so an old queued task cannot publish into a new scene.
            BlockDataQueue.CaptureToken token = SectionBuildCapture.tokenOr(
                    ((RegionCaptureAccess) region).rtxremix$captureToken());
            RtxRemixMod mod = RtxRemixMod.getInstance();
            BlockTextureManager textures = mod == null || mod.getRenderer() == null
                    ? null : mod.getRenderer().getTextureManager();
            emitter = BlockQuadEmitter.threadLocal();
            try (ProfileScope ignored = ProfileScope.begin("SectionCompiler")) {
                emitter.beginSection(sectionPos, textures, blockModelSet);
                SectionCompiler.Results result;
                try (ProfileScope blockLoop = ProfileScope.begin("block loop")) {
                    result = original.call(sectionPos, region, sorting, buffers);
                }
                BlockDataQueue.enqueue(emitter.finishSection(sectionPos), token);
                return result;
            }
        } finally {
            if (emitter != null && emitter.isActive()) {
                emitter.discardSection();
            }
            VanillaSectionBuilds.endBuild(capturing);
            rtxremix$captureForBuild.set(Boolean.FALSE);
        }
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState rtxremix$captureBlock(RenderSectionRegion region, BlockPos pos) {
        BlockState state = region.getBlockState(pos);
        if (rtxremix$captureForBuild.get()) {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            // Include air: its open boundary cells are part of the light flood.
            emitter.processBlock(state, region, pos, state.isSolidRender());
            if (!state.isAir() && BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) {
                emitter.emitBlock(state, pos, region);
            }
        }
        return state;
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$skipRasterBlock(BlockState state) {
        return rtxremix$captureForBuild.get() ? RenderShape.INVISIBLE
                : BlockQuadEmitter.getRenderTypeCached(state);
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$captureFluid(FluidRenderer renderer, BlockAndTintGetter region,
                                     BlockPos pos, FluidRenderer.Output output, BlockState block,
                                     FluidState fluid, Operation<Void> original) {
        if (rtxremix$captureForBuild.get()) {
            BlockQuadEmitter.threadLocal().emitFluid(pos, region, fluid);
        } else {
            original.call(renderer, region, pos, output, block, fluid);
        }
    }
}
