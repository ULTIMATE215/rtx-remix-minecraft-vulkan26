/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessor {
    @Invoker("prepareFrameWithContext")
    FeatureRenderDispatcher.PreparedFrame rtxremix$prepareFrame(FeatureFrameContext context, SubmitNodeStorage nodes);

    @Accessor("preparedFrame")
    FeatureRenderDispatcher.PreparedFrame rtxremix$preparedFrame();
}
