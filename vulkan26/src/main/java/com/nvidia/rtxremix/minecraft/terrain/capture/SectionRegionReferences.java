/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/** Releases the extraction frame's ownership after submission or when the frame is discarded. */
public interface SectionRegionReferences {
    void rtxremix$releaseSectionRegions();
}
