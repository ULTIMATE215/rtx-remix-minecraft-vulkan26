/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * DLSS path selection for the next native startup. Remix initializes lighting and denoiser
 * settings from this path at device creation; a live preset-number write alone is insufficient.
 * The default delegates to Remix without changing any native preferences.
 */
public final class DlssSettings {
    public static final String ID = "dlss.mode";
    public static final String REMIX = "remix";
    public static final String SR_M = "sr45";
    public static final String SR_L = "sr45-l";
    public static final String RR = "rr";
    public static final List<String> MODES = List.of(REMIX, SR_M, SR_L, RR);

    private String selected = REMIX;
    private String frameGeneration = REMIX;
    private final RtxSetting<String> frameGenerationSetting = RtxSetting.ofChoice(
            "dlss.frameGeneration", SettingGroup.DLSS, "rtxremix.setting.dlss.frameGeneration",
            "rtxremix.setting.dlss.frameGeneration.tip", List.of(REMIX, "off", "on"),
            "rtxremix.settings.value.frameGeneration.", REMIX, () -> frameGeneration, value -> frameGeneration = value);
    private final RtxSetting<String> setting = RtxSetting.ofChoice(
            ID, SettingGroup.DLSS, "rtxremix.setting.dlss.mode", "rtxremix.setting.dlss.mode.tip",
            MODES, "rtxremix.settings.value.dlss.", REMIX, () -> selected, value -> selected = value);

    public RtxSetting<String> setting() {
        return setting;
    }

    public RtxSetting<String> frameGenerationSetting() { return frameGenerationSetting; }

    /**
     * Call after loading the library and before Startup, so Remix derives the matching path-tracer
     * settings. Frame generation changes only when explicitly selected. Quality and the saved
     * sparse-rendering preference stay intact; the runtime requires RR for sparse rendering.
     */
    public void applyBeforeStartup(BiConsumer<String, String> configWriter) {
        if (!REMIX.equals(frameGeneration)) {
            configWriter.accept("rtx.dlfg.enable", Boolean.toString("on".equals(frameGeneration)));
        }
        if (REMIX.equals(selected)) {
            return;
        }
        // Custom prevents the combined On/Off preset from resetting the upscaler or quality.
        configWriter.accept("rtx.dlssPreset", "2");
        configWriter.accept("rtx.upscalerType", "1");
        configWriter.accept("rtx.enableRayReconstruction", Boolean.toString(RR.equals(selected)));
        if (!RR.equals(selected)) {
            // NGX SR L=12 and M=13 are the DLSS 4.5 second-generation Transformer models.
            // These numbers are NOT RR presets: RR has its own D/E/F enum.
            configWriter.accept("rtx.dlss.preset", SR_L.equals(selected) ? "12" : "13");
        }
    }

    /** Resolved Remix options, for an on-demand status query rather than a claim about NGX internals. */
    public static Resolved readResolved(Function<String, String> configReader) {
        if (Integer.parseInt(configReader.apply("rtx.upscalerType").trim()) != 1) {
            return new Resolved("other", "-");
        }
        boolean rr = Boolean.parseBoolean(configReader.apply("rtx.enableRayReconstruction").trim());
        int preset = Integer.parseInt(configReader.apply(
                rr ? "rtx.rayreconstruction.preset" : "rtx.dlss.preset").trim());
        String presetName = switch (preset) {
            case 0 -> "Default";
            case 4 -> rr ? "D" : Integer.toString(preset);
            case 5 -> rr ? "E" : Integer.toString(preset);
            case 6 -> rr ? "F" : Integer.toString(preset);
            case 10 -> !rr ? "J" : Integer.toString(preset);
            case 11 -> !rr ? "K" : Integer.toString(preset);
            case 12 -> !rr ? "L" : Integer.toString(preset);
            case 13 -> !rr ? "M" : Integer.toString(preset);
            default -> Integer.toString(preset);
        };
        return new Resolved(rr ? RR : "sr", presetName);
    }

    public record Resolved(String path, String preset) { }
}
