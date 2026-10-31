/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/** Identity and neighbourhood captured with an immutable render region. */
public interface RegionCaptureAccess extends RegionSnapshotRelease {
    long rtxremix$sectionNode();
    int rtxremix$snapshotMask();
    BlockDataQueue.CaptureToken rtxremix$captureToken();
}
