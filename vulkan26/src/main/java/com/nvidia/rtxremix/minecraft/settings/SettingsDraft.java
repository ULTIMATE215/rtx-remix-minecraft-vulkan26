/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A screen's edits, isolated from the live renderer until the user presses Apply. */
final class SettingsDraft {
    private final RtxSettings settings;
    private final Map<String, Object> baseline = new LinkedHashMap<>();
    private final Map<String, Object> pending = new LinkedHashMap<>();
    private final Set<SettingGroup> appliedGroups = EnumSet.noneOf(SettingGroup.class);

    SettingsDraft(RtxSettings settings) {
        this.settings = settings;
        reload();
    }

    Object get(RtxSetting<?> setting) { return pending.get(setting.id()); }

    void set(RtxSetting<?> setting, Object value) {
        boolean valid = switch (setting.kind()) {
            case BOOL -> value instanceof Boolean;
            case CHOICE -> value instanceof String && setting.choices().contains(value);
            case INT -> value instanceof Integer n && setting.inRange(n) && n % setting.step() == 0;
            case FLOAT -> value instanceof Double n && Double.isFinite(n) && setting.inRange(n);
        };
        if (!valid) throw new IllegalArgumentException("Invalid setting value for " + setting.id());
        pending.put(setting.id(), value);
    }

    boolean changed(RtxSetting<?> setting) {
        return !equal(setting, baseline.get(setting.id()), get(setting));
    }

    boolean changed() {
        return settings.all().stream().anyMatch(this::changed);
    }

    void undo() {
        pending.clear();
        pending.putAll(baseline);
    }

    void reset(SettingGroup group) {
        for (RtxSetting<?> setting : settings.group(group)) pending.put(setting.id(), setting.defaultValue());
    }

    void resetAll() {
        for (RtxSetting<?> setting : settings.all()) pending.put(setting.id(), setting.defaultValue());
    }

    /** Returns changed groups so their expensive rebuilds can be batched once. */
    Set<SettingGroup> apply() {
        Set<SettingGroup> groups = EnumSet.noneOf(SettingGroup.class);
        for (RtxSetting<?> setting : settings.all()) {
            if (!changed(setting)) continue;
            applyValue(setting, get(setting));
            groups.add(setting.group());
            appliedGroups.add(setting.group());
            // Record successful writes immediately; a later failure must not replay them.
            Object accepted = setting.get();
            baseline.put(setting.id(), accepted);
            pending.put(setting.id(), accepted);
        }
        return groups;
    }

    /** Includes successful writes preceding a failed setter, so their rebuilds are not lost. */
    Set<SettingGroup> takeAppliedGroups() {
        Set<SettingGroup> result = Set.copyOf(appliedGroups);
        appliedGroups.clear();
        return result;
    }

    private void reload() {
        for (RtxSetting<?> setting : settings.all()) baseline.put(setting.id(), setting.get());
        undo();
    }

    private static boolean equal(RtxSetting<?> setting, Object left, Object right) {
        return setting.kind() == RtxSetting.Kind.FLOAT
                ? Float.compare(((Number) left).floatValue(), ((Number) right).floatValue()) == 0
                : Objects.equals(left, right);
    }

    @SuppressWarnings("unchecked")
    private static <T> void applyValue(RtxSetting<T> setting, Object value) {
        setting.apply((T) value);
    }
}
