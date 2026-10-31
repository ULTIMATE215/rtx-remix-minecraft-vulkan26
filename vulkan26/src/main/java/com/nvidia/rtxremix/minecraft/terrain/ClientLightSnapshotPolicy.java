/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Chooses immutable light publication and whether the client needs light-only rebuilds.
 * Minecraft 26.2's workers read client light even during capture, so every published map
 * stays separate from the engine's mutable state. The optimization replaces full clones
 * with immutable delta layers; it never aliases the live map.
 */
public final class ClientLightSnapshotPolicy {
    public static final String PROPERTY = "rtxremix.light.vanillaSnapshot";
    public static final boolean LAYERED_ALLOWED = layeredAllowedFrom(System.getProperty(PROPERTY));

    public enum Decision {
        VANILLA,
        LAYERED,
        LAYERED_SKIP_NOTIFICATIONS
    }

    private final boolean clientEngine;
    private final boolean layeredAllowed;

    public ClientLightSnapshotPolicy(boolean clientEngine, boolean layeredAllowed) {
        this.clientEngine = clientEngine;
        this.layeredAllowed = layeredAllowed;
    }

    /** Server notifications always run; a raster build in flight keeps client notifications. */
    public Decision onPublish(boolean rtxCaptureOn, boolean vanillaBuildInFlight) {
        if (!layeredAllowed) {
            return Decision.VANILLA;
        }
        return clientEngine && rtxCaptureOn && !vanillaBuildInFlight
                ? Decision.LAYERED_SKIP_NOTIFICATIONS : Decision.LAYERED;
    }

    static boolean layeredAllowedFrom(String property) {
        return !Boolean.parseBoolean(property);
    }

    public static String effectiveStateLine() {
        return LAYERED_ALLOWED
                ? "Client and server light snapshots: immutable layers (" + PROPERTY + "=false)"
                : "Client and server light snapshots: vanilla per-pass clone (" + PROPERTY + "=true)";
    }
}
