/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Counts section builds running with RTX capture off.
 *
 * <p>A build latches the capture gate once at its start. A raster build can finish after
 * capture switches on because geometry invalidation cancels queued tasks, not running
 * ones. Light publication therefore keeps rebuild notifications until this count is zero.
 * All 26.2 light snapshots remain immutable regardless of this count.
 *
 * <p>Invariant: a build raises the count before it reads the gate. Both accesses are
 * volatile, so a render thread that flips the gate on and then reads zero knows that every
 * raster build has already finished: a build that has not counted itself yet
 * reads the gate after the flip and captures instead.
 */
public final class VanillaSectionBuilds {
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private VanillaSectionBuilds() {
    }

    /**
     * Registers a build that is starting on this thread and latches its capture mode.
     *
     * @param captureGate the RTX capture gate, read exactly once
     * @return the latched capture state; a capturing build is not counted
     */
    public static boolean beginBuild(BooleanSupplier captureGate) {
        IN_FLIGHT.incrementAndGet();
        boolean capturing = captureGate.getAsBoolean();
        if (capturing) {
            IN_FLIGHT.decrementAndGet();
        }
        return capturing;
    }

    /** Unregisters a build that {@link #beginBuild} latched with {@code capturing}. */
    public static void endBuild(boolean capturing) {
        if (!capturing) {
            IN_FLIGHT.decrementAndGet();
        }
    }

    /** Whether a build that latched capture off is running. */
    public static boolean anyInFlight() {
        return IN_FLIGHT.get() > 0;
    }
}
