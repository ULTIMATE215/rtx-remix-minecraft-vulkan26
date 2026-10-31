/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Item metadata that vanilla's geometry state does not otherwise retain. */
public interface ItemRenderStateAccess {
    void rtxremix$setSource(ItemStack stack, LivingEntity holder);
    ItemStack rtxremix$stack();
    boolean rtxremix$claimHeldEffects();
}
