/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Keeps 26.2's cached part selection and removes its per-block list iterator. */
@Mixin(MultiPartModel.class)
public abstract class MultipartBakedModelMixin {
    @Shadow @Final private MultiPartModel.SharedBakedState shared;
    @Shadow @Final private BlockState blockState;
    @Shadow private List<BlockStateModel> models;

    /**
     * @author RTX Remix
     * @reason Preserve vanilla's seed and caller RNG state without allocating an iterator.
     */
    @Overwrite
    public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        if (models == null) {
            models = shared.selectModels(blockState);
        }
        long seed = random.nextLong();
        List<BlockStateModel> selected = models;
        for (int i = 0, size = selected.size(); i < size; i++) {
            // Vanilla resets the caller's random for every selected model, including
            // the last one. A separate random would leave the caller in a different state.
            random.setSeed(seed);
            selected.get(i).collectParts(random, output);
        }
    }
}
