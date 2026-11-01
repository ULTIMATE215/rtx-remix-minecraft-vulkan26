/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DlssSettingsTest {
    private static Map<String, String> nativeSettings() {
        return new HashMap<>(Map.of(
                "rtx.dlssPreset", "0",
                "rtx.upscalerType", "0",
                "rtx.enableRayReconstruction", "True",
                "rtx.dlss.preset", "11",
                "rtx.rayreconstruction.preset", "6",
                "rtx.qualityDLSS", "3",
                "rtx.sparseRendering.enableSparseRendering", "True",
                "rtx.dlfg.enable", "True"));
    }

    @Test
    void defaultLeavesExistingRemixPreferencesInControl() {
        DlssSettings dlss = new DlssSettings();
        dlss.applyBeforeStartup((key, value) -> {
            throw new AssertionError("Default must not override " + key);
        });
        assertTrue(dlss.setting().isDefault());
    }

    @ParameterizedTest
    @CsvSource({"sr45,13,M", "sr45-l,12,L"})
    void aSavedSrModeSelectsTheActualSrPathAtNextStartup(
            String mode, String preset, String presetName, @TempDir Path dir) {
        Map<String, String> runtime = nativeSettings();
        Map<String, String> before = Map.copyOf(runtime);
        DlssSettings current = new DlssSettings();
        current.setting().apply(mode);

        RtxSettingsStore store = new RtxSettingsStore(dir.resolve("rtxremix.json"));
        store.save(new RtxSettings(List.of(current.setting())));
        DlssSettings nextLaunch = new DlssSettings();
        store.loadSetting(nextLaunch.setting());
        nextLaunch.applyBeforeStartup(runtime::put);

        assertEquals(mode, nextLaunch.setting().get());
        assertEquals("1", runtime.get("rtx.upscalerType"));
        assertEquals("false", runtime.get("rtx.enableRayReconstruction"));
        assertEquals("2", runtime.get("rtx.dlssPreset"), "the old combined Off preset must not undo SR");
        assertEquals(preset, runtime.get("rtx.dlss.preset"));
        assertEquals(new DlssSettings.Resolved("sr", presetName), DlssSettings.readResolved(runtime::get));

        // Sparse's checkbox stays the user's preference: RR gates its activity.
        for (String key : List.of("rtx.rayreconstruction.preset", "rtx.qualityDLSS",
                "rtx.sparseRendering.enableSparseRendering", "rtx.dlfg.enable")) {
            assertEquals(before.get(key), runtime.get(key), key);
        }
    }

    @Test
    void selectingRrRestoresItsPathWithoutReplacingItsModelOrSparsePreference() {
        Map<String, String> runtime = nativeSettings();
        DlssSettings dlss = new DlssSettings();
        dlss.setting().apply(DlssSettings.SR_M);
        dlss.applyBeforeStartup(runtime::put);

        dlss.setting().apply(DlssSettings.RR);
        assertEquals("false", runtime.get("rtx.enableRayReconstruction"), "still pending restart");
        dlss.applyBeforeStartup(runtime::put);

        assertEquals("true", runtime.get("rtx.enableRayReconstruction"));
        assertEquals("True", runtime.get("rtx.sparseRendering.enableSparseRendering"));
        assertEquals("6", runtime.get("rtx.rayreconstruction.preset"));
        assertEquals(new DlssSettings.Resolved("rr", "F"), DlssSettings.readResolved(runtime::get),
                "the dormant SR M preset must not be reported as the RR model");
    }

    @Test
    void resetDelegatesToRemixOnFutureLaunches(@TempDir Path dir) {
        RtxSettingsStore store = new RtxSettingsStore(dir.resolve("rtxremix.json"));
        DlssSettings dlss = new DlssSettings();
        dlss.setting().apply(DlssSettings.SR_M);
        store.save(new RtxSettings(List.of(dlss.setting())));
        dlss.setting().reset();
        store.save(new RtxSettings(List.of(dlss.setting())));

        DlssSettings nextLaunch = new DlssSettings();
        store.loadSetting(nextLaunch.setting());
        nextLaunch.applyBeforeStartup((key, value) -> {
            throw new AssertionError("Reset must delegate to native preferences: " + key);
        });
        assertFalse(Files.exists(dir.resolve("rtxremix.json")));
    }

    @Test
    void earlyLoadDoesNotChangeOtherSettingsOrTheFile(@TempDir Path dir) throws Exception {
        String content = """
                {"version":2,"settings":{"dlss.mode":"sr45","post.exposure":0.8,"dist.light":12}}
                """;
        Path file = dir.resolve("rtxremix.json");
        Files.writeString(file, content);
        DlssSettings dlss = new DlssSettings();
        new RtxSettingsStore(file).loadSetting(dlss.setting());
        assertEquals(DlssSettings.SR_M, dlss.setting().get());
        assertEquals(content, Files.readString(file));
    }

    @Test
    void invalidSavedModesLeaveTheRuntimeAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rtxremix.json");
        for (String value : List.of("13", "true", "null", "[]", "{}", "\"sr45-f\"")) {
            Files.writeString(file, "{\"version\":2,\"settings\":{\"dlss.mode\":" + value + "}}");
            DlssSettings dlss = new DlssSettings();
            new RtxSettingsStore(file).loadSetting(dlss.setting());
            assertTrue(dlss.setting().isDefault(), value);
        }
        Files.writeString(file, "{\"version\":3,\"settings\":{\"dlss.mode\":\"sr45\"}}");
        DlssSettings dlss = new DlssSettings();
        new RtxSettingsStore(file).loadSetting(dlss.setting());
        assertTrue(dlss.setting().isDefault());
    }

    @Test
    void unknownChoicesCannotReachTheApplier() {
        DlssSettings dlss = new DlssSettings();
        assertThrows(IllegalArgumentException.class, () -> dlss.setting().apply("future-model"));
        assertTrue(dlss.setting().isDefault());
    }

    @Test
    void statusFollowsTheRuntimeEvenAfterAMenuOverride() {
        Map<String, String> runtime = nativeSettings();
        DlssSettings dlss = new DlssSettings();
        dlss.setting().apply(DlssSettings.SR_M);
        dlss.applyBeforeStartup(runtime::put);
        runtime.put("rtx.upscalerType", "3");

        assertEquals(new DlssSettings.Resolved("other", "-"), DlssSettings.readResolved(runtime::get));
        assertEquals(DlssSettings.SR_M, dlss.setting().get(), "next launch differs from live state");
    }
}
