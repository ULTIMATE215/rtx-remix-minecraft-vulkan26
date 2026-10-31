/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Captures vanilla's weather geometry once, deferring only its GPU draws. */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Shadow @Final private static Identifier RAIN_LOCATION;
    @Shadow @Final private static Identifier SNOW_LOCATION;

    @Shadow
    protected abstract void renderInstances(VertexConsumer vertices, List<WeatherEffectRenderer.ColumnInstance> columns,
                                            Vec3 camera, float opacity, int radius, float intensity);

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureWeather(Vec3 camera, WeatherRenderState state, CallbackInfo callback) {
        if (!RtxRemixMod.isRemixWorldRendering()) return;
        RemixWeatherRenderer renderer = RtxRemixMod.renderer.getWeatherRenderer();
        renderer.beginCaptureFrame(Minecraft.getInstance().gameRenderer.lightmap());
        if (!state.rainColumns.isEmpty()) {
            BufferBuilder rain = renderer.beginQuads();
            renderInstances(rain, state.rainColumns, camera, 1.0f, state.radius, state.intensity);
            renderer.capture(rain.buildOrThrow(), RAIN_LOCATION);
        }
        if (!state.snowColumns.isEmpty()) {
            BufferBuilder snow = renderer.beginQuads();
            renderInstances(snow, state.snowColumns, camera, 0.8f, state.radius, state.intensity);
            renderer.capture(snow.buildOrThrow(), SNOW_LOCATION);
        }
        // The original method only builds meshes and owns a scoped RenderPass;
        // it leaves no global render state that needs replaying after these draws.
        callback.cancel();
    }
}
