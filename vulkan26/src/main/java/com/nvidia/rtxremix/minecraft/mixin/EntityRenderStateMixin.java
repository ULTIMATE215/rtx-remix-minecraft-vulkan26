/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.EntityRenderStateAccess;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityRenderStateAccess {
    @Unique private Entity rtxremix$entity;
    @Unique private float rtxremix$tickDelta;

    @Override public Entity rtxremix$entity() { return rtxremix$entity; }
    @Override public float rtxremix$tickDelta() { return rtxremix$tickDelta; }
    @Override public void rtxremix$setEntity(Entity entity, float tickDelta) {
        rtxremix$entity = entity;
        rtxremix$tickDelta = tickDelta;
    }
}
