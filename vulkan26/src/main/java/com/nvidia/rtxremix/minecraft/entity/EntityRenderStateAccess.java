/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.Entity;

/** Keeps retained-instance ownership attached to vanilla's extracted frame state. */
public interface EntityRenderStateAccess {
    Entity rtxremix$entity();
    float rtxremix$tickDelta();
    void rtxremix$setEntity(Entity entity, float tickDelta);
}
