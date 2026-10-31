/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes each light engine's storage for publishing pending changes on a capture toggle.
 */
@Mixin(LightEngine.class)
public interface ChunkLightProviderAccessor {
    @Accessor("storage")
    LayerLightSectionStorage<?> rtxremix$getLightStorage();
}
