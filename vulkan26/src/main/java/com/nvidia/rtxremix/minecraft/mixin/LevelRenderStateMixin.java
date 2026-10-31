/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionRegionReferences;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(LevelRenderState.class)
public abstract class LevelRenderStateMixin implements SectionRegionReferences {
    @Shadow @Final public List<SectionUpdateRenderState> sectionUpdateRenderStates;
    @Unique private boolean rtxremix$regionsReleased;

    @Override
    public void rtxremix$releaseSectionRegions() {
        if (rtxremix$regionsReleased) return;
        rtxremix$regionsReleased = true;
        for (SectionUpdateRenderState update : sectionUpdateRenderStates) {
            ((RegionSnapshotRelease) update.region()).rtxremix$release();
        }
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void rtxremix$discardPreviousFrame(CallbackInfo ci) {
        rtxremix$releaseSectionRegions();
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void rtxremix$beginExtraction(CallbackInfo ci) {
        rtxremix$regionsReleased = false;
    }
}
