/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeGraphicsSettingsTest {
    @Test void usesTheNativeDlssEnumAndKeepsQualitySeparateFromTheDlssModel() {
        var writes = new HashMap<String,String>();
        var settings = new ArrayList<RtxSetting<?>>();
        NativeGraphicsSettings.addTo(settings, key -> switch (key) {
            case "rtx.qualityDLSS" -> "4";
            case "rtx.sparseRendering.quality" -> "0.3";
            default -> "False";
        },writes::put);
        var quality = settings.stream().filter(s -> s.id().equals("dlss.quality")).findFirst().orElseThrow();
        assertEquals("auto",quality.get());
        @SuppressWarnings("unchecked") var choice = (RtxSetting<String>) quality;
        choice.apply("quality");
        assertEquals("3",writes.get("rtx.qualityDLSS"));
        assertEquals(1,writes.size());
    }
    @Test void failedNativeWriteDoesNotAdvanceCachedValue() {
        var setting = NativeGraphicsSettings.bool("b",SettingGroup.DLSS,"option",k -> "false",
                (k,v) -> { throw new IllegalStateException("write failed"); }).orElseThrow();
        assertThrows(IllegalStateException.class,() -> setting.apply(true));
        assertFalse(setting.get());
    }
    @Test void unexpectedNativeValuesAreNotSilentlyShownAsValidSettings() {
        assertTrue(NativeGraphicsSettings.bool("b",SettingGroup.DLSS,"option",k -> "not-supported",(k,v) -> fail()).isEmpty());
        assertTrue(NativeGraphicsSettings.number("n",SettingGroup.DLSS,"option",0,1,k -> "NaN",(k,v) -> fail()).isEmpty());
        assertTrue(NativeGraphicsSettings.choice("c",SettingGroup.DLSS,"option",List.of("a","b"),k -> "9",(k,v) -> fail()).isEmpty());
    }
}
