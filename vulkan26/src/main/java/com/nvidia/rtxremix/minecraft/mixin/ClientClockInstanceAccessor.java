/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.ClientClockManager$ClockInstance")
public interface ClientClockInstanceAccessor {
    @Accessor("totalTicks")
    void rtxremix$setTotalTicks(long ticks);

    @Accessor("partialTick")
    void rtxremix$setPartialTick(float partialTick);
}
