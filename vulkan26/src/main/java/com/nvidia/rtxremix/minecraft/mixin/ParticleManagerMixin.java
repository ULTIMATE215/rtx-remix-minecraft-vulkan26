/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.profiling.ParticleTickProfiler;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/** Coarse ticking markers, with individual group scopes owned by ParticleGroupMixin. */
@Mixin(ParticleEngine.class)
public abstract class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;

    @WrapMethod(method = "tick")
    private void rtxremix$profileTick(Operation<Void> original) {
        ParticleTickProfiler.beginTick();
        int activeParticles = 0;
        int activeGroups = 0;
        for (ParticleGroup<?> group : particles.values()) {
            int size = group.size();
            activeParticles += size;
            if (size > 0) activeGroups++;
        }
        try (ProfileScope scope = ProfileScope.beginJfrOnly("ParticleEngine.tick")
                .captureCount(activeParticles).layerCount(activeGroups)
                .plotOnClose("Java.ParticleEngine.tick.ms")) {
            try {
                original.call();
            } finally {
                ParticleTickProfiler.Stats stats = ParticleTickProfiler.current();
                scope.collisionChecks(stats.collisionChecks())
                        .collisionSkips(stats.skippedCollisionChecks())
                        .collisionNanos(stats.collisionNanos());
                ProfileScope.plot("Java.ParticleEngine.collisionChecks", stats.collisionChecks());
                ProfileScope.plot("Java.ParticleEngine.collisionSkips", stats.skippedCollisionChecks());
                ProfileScope.plot("Java.ParticleEngine.collisionMs", stats.collisionNanos() / 1_000_000.0);
            }
        }
    }
}
