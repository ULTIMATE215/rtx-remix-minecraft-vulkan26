/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads/writes non-default setting overrides as {version, settings:{id:value}}. */
public final class RtxSettingsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RtxSettingsStore.class);
    // A file whose version does not match is rejected, never converted: the
    // meaning of a stored value can change with the version, and a mismatched
    // file read as if it matched is a silent wrong setting. Defaults re-apply
    // on the next launch.
    private static final int VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public RtxSettingsStore(Path file) {
        this.file = file;
    }

    public void save(RtxSettings settings) {
        try {
            saveChecked(settings);
        } catch (IOException e) {
            LOGGER.warn("Failed to save RTX settings to {}", file, e);
        }
    }

    /** Settings screens surface a write failure and let the user retry Apply. */
    public void saveChecked(RtxSettings settings) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject values = new JsonObject();
        for (RtxSetting<?> s : settings.all()) {
            if (s.isDefault()) {
                continue;
            }
            switch (s.kind()) {
                case BOOL -> values.addProperty(s.id(), (Boolean) s.get());
                case INT -> values.addProperty(s.id(), (Integer) s.get());
                case FLOAT -> values.addProperty(s.id(), (Double) s.get());
                case CHOICE -> values.addProperty(s.id(), (String) s.get());
            }
        }
        root.add("settings", values);
        if (values.size() == 0) {
            Files.deleteIfExists(file);
            return;
        }
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "rtxremix-", ".json.tmp");
        try {
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void load(RtxSettings settings) {
        JsonObject values = readValues();
        if (values == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            RtxSetting<?> setting = settings.byId(entry.getKey());
            if (setting == null) {
                LOGGER.warn("Unknown RTX setting id '{}' in config, skipping", entry.getKey());
                continue;
            }
            applyEntry(setting, entry.getValue());
        }
    }

    /** Loads a startup preference before the live renderer and its other settings exist. */
    public void loadSetting(RtxSetting<?> setting) {
        JsonObject values = readValues();
        if (values != null && values.has(setting.id())) {
            applyEntry(setting, values.get(setting.id()));
        }
    }

    private JsonObject readValues() {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != VERSION) {
                LOGGER.warn("RTX settings file {} is version {}, expected {}; ignoring it and "
                        + "using defaults, because a file from another version cannot be read "
                        + "safely.", file, version, VERSION);
                return null;
            }
            if (!root.has("settings")) {
                return null;
            }
            return root.getAsJsonObject("settings");
        } catch (Exception e) {
            LOGGER.warn("Failed to read RTX settings from {}, ignoring", file, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void applyEntry(RtxSetting<?> setting, JsonElement value) {
        try {
            switch (setting.kind()) {
                case BOOL -> {
                    boolean v = value.getAsBoolean();
                    RtxSetting<Boolean> typed = (RtxSetting<Boolean>) setting;
                    if (!typed.isDefaultValue(v)) {
                        typed.apply(v);
                    }
                }
                case INT -> {
                    int v = value.getAsInt();
                    if (!setting.inRange(v)) {
                        warnRange(setting, v);
                        return;
                    }
                    RtxSetting<Integer> typed = (RtxSetting<Integer>) setting;
                    if (!typed.isDefaultValue(v)) {
                        typed.apply(v);
                    }
                }
                case FLOAT -> {
                    double v = value.getAsDouble();
                    if (!setting.inRange(v)) {
                        warnRange(setting, v);
                        return;
                    }
                    RtxSetting<Double> typed = (RtxSetting<Double>) setting;
                    if (!typed.isDefaultValue(v)) {
                        typed.apply(v);
                    }
                }
                case CHOICE -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("Choice must be a string id");
                    }
                    String v = value.getAsString();
                    RtxSetting<String> typed = (RtxSetting<String>) setting;
                    if (!typed.isDefaultValue(v)) {
                        typed.apply(v);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to apply saved RTX setting '{}', skipping", setting.id(), e);
        }
    }

    private void warnRange(RtxSetting<?> setting, double v) {
        LOGGER.warn("Saved RTX setting '{}' value {} out of range [{}, {}], skipping",
                setting.id(), v, setting.min(), setting.max());
    }
}
