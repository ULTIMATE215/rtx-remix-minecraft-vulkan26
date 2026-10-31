/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/** Whether a section copy came from a loaded column, including an empty section. */
public interface SectionSnapshotAccess extends RegionSnapshotRelease {
    boolean rtxremix$columnLoaded();
}
