/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Identifies the owner of a block entity's already extracted geometry submission. */
public interface BlockEntityRenderStateAccess {
    BlockEntity rtxremix$blockEntity();
    void rtxremix$setBlockEntity(BlockEntity blockEntity);
}
