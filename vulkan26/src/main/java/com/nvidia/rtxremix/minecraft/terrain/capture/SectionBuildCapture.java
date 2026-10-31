/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/** Carries the compile task's cancellation identity through vanilla's section compiler. */
public final class SectionBuildCapture {
    private static final ThreadLocal<BlockDataQueue.CaptureToken> CURRENT = new ThreadLocal<>();

    private SectionBuildCapture() {}

    public static void begin(BlockDataQueue.CaptureToken token) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested terrain compile task");
        CURRENT.set(token);
    }

    public static BlockDataQueue.CaptureToken tokenOr(BlockDataQueue.CaptureToken snapshot) {
        BlockDataQueue.CaptureToken task = CURRENT.get();
        return task != null ? task : snapshot;
    }

    public static void end() {
        CURRENT.set(null);
    }
}
