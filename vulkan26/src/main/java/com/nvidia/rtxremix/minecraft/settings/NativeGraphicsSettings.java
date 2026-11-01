/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.RemixException;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validated native bindings. The cache advances only after a successful native write. */
final class NativeGraphicsSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeGraphicsSettings.class);
    private NativeGraphicsSettings() {}

    static void addTo(List<RtxSetting<?>> list, Function<String, String> reader, BiConsumer<String, String> writer) {
        choice("dlss.quality", SettingGroup.DLSS, "rtx.qualityDLSS",
                List.of("ultraPerformance", "performance", "balanced", "quality", "auto", "dlaa"),
                reader, writer).ifPresent(list::add);
        bool("sparse.enabled", SettingGroup.SPARSE_RENDERING, "rtx.sparseRendering.enableSparseRendering",
                reader, writer).ifPresent(list::add);
        number("sparse.quality", SettingGroup.SPARSE_RENDERING, "rtx.sparseRendering.quality", 0, 1,
                reader, writer).ifPresent(list::add);
        bool("materials.anisotropic", SettingGroup.MATERIALS, "rtx.useAnisotropicFiltering", reader, writer).ifPresent(list::add);
    }

    static Optional<RtxSetting<Boolean>> bool(String id, SettingGroup group, String option,
            Function<String, String> reader, BiConsumer<String, String> writer) {
        try {
            String resolved = reader.apply(option).trim();
            if (!resolved.equalsIgnoreCase("true") && !resolved.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Expected boolean, got " + resolved);
            }
            boolean[] value = { Boolean.parseBoolean(resolved) };
            return Optional.of(RtxSetting.ofBool(id, group, key(id), key(id) + ".tip", value[0], () -> value[0], v -> {
                writer.accept(option, Boolean.toString(v));
                value[0] = v;
            }));
        } catch (RemixException | IllegalArgumentException failure) {
            unavailable(option, failure);
            return Optional.empty();
        }
    }

    static Optional<RtxSetting<Double>> number(String id, SettingGroup group, String option, double min, double max,
            Function<String, String> reader, BiConsumer<String, String> writer) {
        try {
            double[] value = { Double.parseDouble(reader.apply(option).trim()) };
            if (!Double.isFinite(value[0]) || value[0] < min || value[0] > max) throw new IllegalArgumentException("Out of range");
            return Optional.of(RtxSetting.ofFloat(id, group, key(id), key(id) + ".tip", min, max, value[0], () -> value[0], v -> {
                if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException("Out of range");
                writer.accept(option, Double.toString(v));
                value[0] = v;
            }));
        } catch (RemixException | IllegalArgumentException failure) {
            unavailable(option, failure);
            return Optional.empty();
        }
    }

    static Optional<RtxSetting<String>> choice(String id, SettingGroup group, String option, List<String> choices,
            Function<String, String> reader, BiConsumer<String, String> writer) {
        try {
            int index = Integer.parseInt(reader.apply(option).trim());
            if (index < 0 || index >= choices.size()) throw new IllegalArgumentException("Unknown enum value " + index);
            String[] value = { choices.get(index) };
            return Optional.of(RtxSetting.ofChoice(id, group, key(id), key(id) + ".tip", choices,
                    "rtxremix.settings.value." + id + ".", value[0], () -> value[0], v -> {
                        writer.accept(option, Integer.toString(choices.indexOf(v)));
                        value[0] = v;
                    }));
        } catch (RemixException | IllegalArgumentException failure) {
            unavailable(option, failure);
            return Optional.empty();
        }
    }

    private static String key(String id) { return "rtxremix.setting." + id; }
    private static void unavailable(String option, RuntimeException failure) {
        LOGGER.warn("The loaded Remix runtime cannot expose {} in settings: {}", option, failure.getMessage());
    }
}
