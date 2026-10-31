/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Snapshot control of one client light storage, implemented by the LightStorage mixin.
 */
public interface LightStorageSnapshotAccess {

    /**
     * Publishes pending changes as a separate immutable snapshot. Render thread only.
     */
    void rtxremix$publishRealSnapshot();
}
