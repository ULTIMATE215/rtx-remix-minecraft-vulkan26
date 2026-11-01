/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;

/** Evaluates a fluid model's optional tint with vanilla's untinted fallback. */
public final class FluidTint {
    private FluidTint() {}

    public static int color(FluidModel model, BlockAndTintGetter world, BlockPos pos) {
        BlockTintSource source = model.tintSource();
        // Vanilla lava has no tint source. White preserves its texture's own color.
        return source == null ? 0xFFFFFFFF : source.colorInWorld(world.getBlockState(pos), world, pos);
    }
}
