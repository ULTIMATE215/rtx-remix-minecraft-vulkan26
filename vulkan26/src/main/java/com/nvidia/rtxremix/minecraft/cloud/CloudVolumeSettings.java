/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.List;

/** Authored cloud parameters, independent of GPU resources and world lifetime. */
public final class CloudVolumeSettings {
    public static final List<String> QUALITIES = List.of("fast", "balanced", "high");
    private boolean enabled = true;
    private String quality = "balanced";
    private float thickness = 96.0f;
    private float coverage = 0.55f;
    private float density = 1.0f;
    private float windSpeed = 1.0f;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) {
        if (!QUALITIES.contains(quality)) throw new IllegalArgumentException("Unknown cloud quality: " + quality);
        this.quality = quality;
    }
    public int steps() { return switch (quality) { case "fast" -> 40; case "high" -> 112; default -> 72; }; }
    public float getThickness() { return thickness; }
    public void setThickness(float value) { thickness = checked(value, 16, 256); }
    public float getCoverage() { return coverage; }
    public void setCoverage(float value) { coverage = checked(value, 0, 1); }
    public float getDensity() { return density; }
    public void setDensity(float value) { density = checked(value, 0.1f, 3); }
    public float getWindSpeed() { return windSpeed; }
    public void setWindSpeed(float value) { windSpeed = checked(value, 0, 4); }

    private static float checked(float value, float min, float max) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Cloud parameter outside [" + min + ", " + max + "]");
        }
        return value;
    }
}
