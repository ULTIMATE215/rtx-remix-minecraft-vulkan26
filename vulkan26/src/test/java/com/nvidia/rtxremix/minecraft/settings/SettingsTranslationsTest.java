/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTranslationsTest {
    @Test void chineseIncludesEveryLabelValueAndDescription() throws Exception {
        var english = load("en_us");
        var chinese = load("zh_cn");
        assertEquals(english.keySet(),chinese.keySet());
        for (String key : english.keySet()) {
            assertFalse(chinese.get(key).getAsString().isBlank(),key);
            if (key.startsWith("rtxremix.setting.")) assertTrue(chinese.get(key).getAsString().codePoints()
                    .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),key);
        }
    }
    private static JsonObject load(String locale) throws Exception {
        try (var stream = SettingsTranslationsTest.class.getResourceAsStream("/assets/rtx-remix-renderer/lang/" + locale + ".json")) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
