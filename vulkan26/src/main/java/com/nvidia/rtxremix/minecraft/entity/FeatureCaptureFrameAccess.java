/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/** Allows a failed CPU feature preparation to close its partially opened frame. */
public interface FeatureCaptureFrameAccess {
    boolean rtxremix$isOpen();
}
