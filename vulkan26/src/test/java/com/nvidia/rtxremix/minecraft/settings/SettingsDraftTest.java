/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsDraftTest {
    @Test void editsUndoAndDefaultsLeaveTheRendererAloneUntilApply() {
        var live = new AtomicReference<>(true);
        var writes = new AtomicInteger();
        var setting = RtxSetting.ofBool("clouds",SettingGroup.CLOUDS,"name","tip",false,live::get,v -> { live.set(v); writes.incrementAndGet(); });
        var draft = new SettingsDraft(new RtxSettings(List.of(setting)));
        draft.resetAll();
        assertEquals(false,draft.get(setting));
        assertTrue(live.get());
        assertEquals(0,writes.get());
        draft.undo();
        assertFalse(draft.changed());
        draft.set(setting,false);
        assertEquals(Set.of(SettingGroup.CLOUDS),draft.apply());
        assertFalse(live.get());
        assertFalse(draft.changed());
        draft.apply();
        assertEquals(1,writes.get());
    }

    @Test void successfulWritesBeforeAFailureAreNotRepeatedAndStillRequestTheirRebuild() {
        var live = new AtomicReference<>(false);
        var writes = new AtomicInteger();
        var first = RtxSetting.ofBool("first",SettingGroup.BLOCK_LIGHTS,"name","tip",false,live::get,v -> { live.set(v); writes.incrementAndGet(); });
        var last = RtxSetting.ofBool("last",SettingGroup.DLSS,"name","tip",false,() -> false,v -> { throw new IllegalStateException("native failure"); });
        var draft = new SettingsDraft(new RtxSettings(List.of(first,last)));
        draft.set(first,true); draft.set(last,true);
        assertThrows(IllegalStateException.class,draft::apply);
        assertTrue(live.get());
        assertFalse(draft.changed(first));
        assertTrue(draft.changed(last));
        assertEquals(Set.of(SettingGroup.BLOCK_LIGHTS),draft.takeAppliedGroups());
        assertTrue(draft.takeAppliedGroups().isEmpty());
        assertThrows(IllegalStateException.class,draft::apply);
        assertEquals(1,writes.get());
    }

    @Test void resettingOneGroupPreservesEditsInOtherGroups() {
        var cloud = RtxSetting.ofBool("cloud",SettingGroup.CLOUDS,"name","tip",false,() -> false,v -> {});
        var light = RtxSetting.ofBool("light",SettingGroup.SKY,"name","tip",false,() -> false,v -> {});
        var draft = new SettingsDraft(new RtxSettings(List.of(cloud,light)));
        draft.set(cloud,true); draft.set(light,true);
        draft.reset(SettingGroup.CLOUDS);
        assertEquals(false,draft.get(cloud));
        assertEquals(true,draft.get(light));
    }

    @Test void validatesFiniteRangesChoicesAndIntegerSteps() {
        var value = RtxSetting.ofFloat("float",SettingGroup.CLOUDS,"name","tip",0,1,0.5,() -> 0.5,v -> {});
        var integer = RtxSetting.ofInt("integer",SettingGroup.CLOUDS,"name","tip",0,10,0,() -> 0,v -> {}).withStep(2);
        var choice = RtxSetting.ofChoice("choice",SettingGroup.CLOUDS,"name","tip",List.of("a","b"),"value.","a",() -> "a",v -> {});
        var draft = new SettingsDraft(new RtxSettings(List.of(value,integer,choice)));
        assertThrows(IllegalArgumentException.class,() -> draft.set(value,Double.NaN));
        assertThrows(IllegalArgumentException.class,() -> draft.set(value,1.1));
        assertThrows(IllegalArgumentException.class,() -> draft.set(integer,3));
        assertThrows(IllegalArgumentException.class,() -> draft.set(choice,"c"));
    }

    @Test void floatBackedValuesDoNotAcquirePhantomEdits() {
        var value = RtxSetting.ofFloat("float",SettingGroup.CLOUDS,"name","tip",0,1,0.45,() -> (double) 0.45f,v -> fail("unexpected setter"));
        var draft = new SettingsDraft(new RtxSettings(List.of(value)));
        draft.resetAll();
        assertFalse(draft.changed());
        assertTrue(draft.apply().isEmpty());
    }
}
