/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Measures the separate label/value columns of the flat settings screen with the game font. */
class SettingsRowWidthTest {
    private static final String LABEL_PREFIX = "rtxremix.setting.";
    private static final String TOOLTIP_SUFFIX = ".tip";
    private static final String BLOCK_LIGHT_NAME_PREFIX = "rtxremix.setting.blockLights.";
    private static final int VALUE_WIDTH = 146;
    private static final int LABEL_WIDTH = SettingsLayout.of(320,240).listWidth() - VALUE_WIDTH - 24;
    private static final Map<String,Integer> BUTTON_WIDTHS = Map.of(
            "rtxremix.settings.resetAll", SettingsLayout.of(320,240).footerWidth(),
            "rtxremix.settings.undo", SettingsLayout.of(320,240).footerWidth(),
            "rtxremix.settings.apply", SettingsLayout.of(320,240).footerWidth(),
            "rtxremix.settings.resetGroup", 44);

    private final DefaultFont font = DefaultFont.load();
    private final Map<String, String> lang = loadLang();

    @Test
    void everyOptionLabelFitsItsColumnAtTheMinimumGuiWidth() {
        List<String> failures = new ArrayList<>();
        for (var entry : settingLabels().entrySet()) {
            int width = font.width(entry.getValue());
            if (width > LABEL_WIDTH) failures.add(entry.getKey() + " is " + width + " px: " + entry.getValue());
        }
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }

    @Test
    void everyChoiceValueFitsWithoutScrolling() {
        for (var entry : lang.entrySet()) if (entry.getKey().startsWith("rtxremix.settings.value.")) {
            assertTrue(font.width(entry.getValue()) <= VALUE_WIDTH - 6,entry.toString());
        }
        for (String value : List.of("-128.00","256.00","50.00×","Unlimited")) {
            assertTrue(font.width(value) <= VALUE_WIDTH - 6,value);
        }
    }

    @Test
    void everyButtonLabelFitsItsWidget() {
        for (var button : BUTTON_WIDTHS.entrySet()) {
            String label = lang.get(button.getKey());
            assertNotNull(label,button.getKey());
            assertTrue(font.width(label) <= button.getValue() - 6,button.getKey() + ": " + label);
        }
    }

    @Test
    void theMinimumLayoutKeepsIndependentLabelAndValueColumns() {
        assertTrue(LABEL_WIDTH >= 120);
        assertTrue(VALUE_WIDTH < SettingsLayout.of(320,240).listWidth() / 2);
    }

    private Map<String, String> settingLabels() {
        Map<String, String> labels = new HashMap<>();
        for (Map.Entry<String, String> line : lang.entrySet()) {
            String key = line.getKey();
            if (key.startsWith(LABEL_PREFIX) && !key.endsWith(TOOLTIP_SUFFIX)) {
                labels.put(key, line.getValue());
            }
        }
        assertFalse(labels.isEmpty(), "no " + LABEL_PREFIX + "* labels in en_us.json");
        return labels;
    }

    private static Map<String, String> loadLang() {
        JsonObject json = readJson("/assets/rtx-remix-renderer/lang/en_us.json");
        Map<String, String> lang = new HashMap<>();
        for (Map.Entry<String, JsonElement> line : json.entrySet()) {
            lang.put(line.getKey(), line.getValue().getAsString());
        }
        return lang;
    }

    private static JsonObject readJson(String resource) {
        try (InputStream in = open(resource)) {
            return JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = SettingsRowWidthTest.class.getResourceAsStream(resource);
        assertNotNull(in, resource + " is not on the test classpath");
        return in;
    }

    /**
     * Advances of Minecraft's default font, computed the way {@code BitmapFont}
     * computes them: the rightmost opaque column of a glyph cell plus one pixel
     * of spacing, scaled by the provider's height. Providers are consulted in
     * declaration order and the first that supplies a code point wins, as in
     * {@code FontStorage}. Only the bitmap providers are loaded, so a string
     * that needs the Unifont fallback fails loudly instead of measuring short.
     */
    private static final class DefaultFont {

        private final Map<Integer, Integer> advances = new HashMap<>();

        static DefaultFont load() {
            DefaultFont font = new DefaultFont();
            font.loadSpaceProvider(readJson("/assets/minecraft/font/include/space.json"));
            for (JsonElement provider
                    : readJson("/assets/minecraft/font/include/default.json").getAsJsonArray("providers")) {
                font.loadBitmapProvider(provider.getAsJsonObject());
            }
            return font;
        }

        int width(String text) {
            int width = 0;
            for (int codePoint : text.codePoints().toArray()) {
                Integer advance = advances.get(codePoint);
                assertNotNull(advance, String.format(
                        "U+%04X in \"%s\" is not in the default font's bitmap providers",
                        codePoint, text));
                width += advance;
            }
            return width;
        }

        private void loadSpaceProvider(JsonObject spaceFont) {
            JsonObject provider = spaceFont.getAsJsonArray("providers").get(0).getAsJsonObject();
            for (Map.Entry<String, JsonElement> advance
                    : provider.getAsJsonObject("advances").entrySet()) {
                advances.putIfAbsent(advance.getKey().codePointAt(0), advance.getValue().getAsInt());
            }
        }

        private void loadBitmapProvider(JsonObject provider) {
            String file = provider.get("file").getAsString();
            String texture = "/assets/minecraft/textures/" + file.substring(file.indexOf(':') + 1);
            BufferedImage image = readImage(texture);
            JsonArray rows = provider.getAsJsonArray("chars");
            int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
            int cellWidth = image.getWidth() / columns;
            int cellHeight = image.getHeight() / rows.size();
            float scale = (provider.has("height") ? provider.get("height").getAsInt() : 8)
                    / (float) cellHeight;
            for (int row = 0; row < rows.size(); row++) {
                int[] codePoints = rows.get(row).getAsString().codePoints().toArray();
                for (int column = 0; column < codePoints.length; column++) {
                    if (codePoints[column] == 0) {
                        continue;
                    }
                    int glyphWidth = glyphWidth(image, cellWidth, cellHeight, column, row);
                    advances.putIfAbsent(codePoints[column], (int) (0.5 + glyphWidth * scale) + 1);
                }
            }
        }

        private static int glyphWidth(BufferedImage image, int cellWidth, int cellHeight,
                int column, int row) {
            for (int x = cellWidth - 1; x >= 0; x--) {
                for (int y = 0; y < cellHeight; y++) {
                    int alpha = image.getRGB(column * cellWidth + x, row * cellHeight + y) >>> 24;
                    if (alpha != 0) {
                        return x + 1;
                    }
                }
            }
            return 0;
        }

        private static BufferedImage readImage(String resource) {
            try (InputStream in = open(resource)) {
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
