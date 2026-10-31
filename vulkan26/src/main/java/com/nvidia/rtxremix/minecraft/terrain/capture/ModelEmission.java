/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModelPart;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Detects custom Fabric emitters once per class without probing or emitting each model twice. */
final class ModelEmission {
    static final ClassValue<Boolean> CUSTOM_MODEL = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> owner = type.getMethod("emitQuads", QuadEmitter.class, BlockAndTintGetter.class,
                        BlockPos.class, BlockState.class, RandomSource.class, Predicate.class).getDeclaringClass();
                return owner != FabricBlockStateModel.class && owner != BlockStateModel.class;
            } catch (NoSuchMethodException ignored) {
                return false;
            }
        }
    };

    static final ClassValue<Boolean> CUSTOM_PART = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> owner = type.getMethod("emitQuads", QuadEmitter.class, Predicate.class).getDeclaringClass();
                return owner != FabricBlockStateModelPart.class && owner != BlockStateModelPart.class;
            } catch (NoSuchMethodException ignored) {
                return false;
            }
        }
    };

    private ModelEmission() {}
}
