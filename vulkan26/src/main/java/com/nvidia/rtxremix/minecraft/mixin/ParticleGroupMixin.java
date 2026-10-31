/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ParticleGroup.class)
public abstract class ParticleGroupMixin {
    @Shadow public abstract int size();

    @WrapMethod(method = "tickParticles")
    private void rtxremix$profileGroup(Operation<Void> original) {
        try (ProfileScope scope = ProfileScope.beginJfrOnly("ParticleEngine.tickParticles")
                .captureCount(size()).plotOnClose("Java.ParticleEngine.tickParticles.ms")) {
            original.call();
        }
    }
}
