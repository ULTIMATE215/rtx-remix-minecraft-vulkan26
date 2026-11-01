/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.vulkan.RemixVulkanSurface;
import com.nvidia.rtxremix.minecraft.vulkan.VulkanTextureBlit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.vulkan.VK10.*;

/** Depth-aware cloud volume composited after reconstruction and before weather, overlays and HUD. */
final class VolumetricCloudRenderer implements AutoCloseable {
    static final String SHADER_RESOURCE = "/assets/rtx-remix-renderer/shaders/cloud_volume.comp";
    private final CloudVolumeSettings settings;
    private final ByteBuffer parameters = BufferUtils.createByteBuffer(CloudVolumeResources.PARAMETER_BYTES);
    private CloudVolumeResources resources;
    private GpuTexture output;
    private GpuTextureView outputView;
    private String shader;

    VolumetricCloudRenderer(CloudVolumeSettings settings) { this.settings = settings; }

    GpuTextureView render(ClientLevel world, Camera camera, float delta, float heightOffset, float size,
                          LightManager.SkyLightState light, GpuTextureView scene, GpuTextureView depth) {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        if (!settings.isEnabled() || world == null || scene == null || depth == null
                || client.options.getCloudStatus() == CloudStatus.OFF
                || world.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD) return scene;
        var probe = camera.attributeProbe();
        float bottom = probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT, delta) + heightOffset;
        int cloudColor = probe.getValue(EnvironmentAttributes.CLOUD_COLOR, delta);
        if (!Float.isFinite(bottom) || (cloudColor >>> 24) == 0 || settings.getCoverage() == 0) return scene;
        var device = RemixVulkanSurface.from(client.windowSurface()).device();
        var encoder = device.createCommandEncoder();
        long sceneHandle = ((VulkanGpuTextureView) scene).vkImageView();
        long depthHandle = ((VulkanGpuTextureView) depth).vkImageView();
        if (resources == null || resources.sceneView != sceneHandle || resources.depthView != depthHandle
                || resources.width != scene.getWidth(0) || resources.height != scene.getHeight(0)) {
            close();
            if (shader == null) shader = loadShader();
            try {
                resources = new CloudVolumeResources(device.vkDevice(), device.vma(), shader,
                        scene.getWidth(0), scene.getHeight(0), sceneHandle, depthHandle);
                output = device.createTexture("Remix world with volume clouds",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                        scene.texture().getFormat(), scene.getWidth(0), scene.getHeight(0), 1, 1);
                outputView = device.createTextureView(output);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
        fillParameters(world, camera, delta, bottom, size, cloudColor, light, scene.getWidth(0), scene.getHeight(0));
        try (ProfileScope ignored = ProfileScope.begin("Clouds.Volume"); MemoryStack stack = MemoryStack.stackPush()) {
            var commands = encoder.allocateAndBeginTransientCommandBuffer();
            resources.record(commands, parameters);
            VulkanTextureBlit.record(commands, resources.outputImage(), 0, resources.width, resources.height,
                    VulkanTextureBlit.image(outputView), 0, resources.width, resources.height, false, VK_FILTER_NEAREST, stack);
            CloudVolumeResources.barrier(commands, stack);
            CloudVolumeResources.check(vkEndCommandBuffer(commands), "finish cloud volume commands");
            encoder.execute(commands);
        }
        return outputView;
    }

    private void fillParameters(ClientLevel world, Camera camera, float delta, float bottom, float size,
                                int color, LightManager.SkyLightState light, int width, int height) {
        var position = camera.position();
        double time = world.getGameTime() + (double) delta;
        float wind = settings.getWindSpeed();
        vector(0, periodic((position.x - time * 0.035 * wind) / size), (float) position.y,
                periodic((position.z - time * 0.012 * wind) / size), bottom);
        double yaw = Math.toRadians(-camera.yRot()), pitch = Math.toRadians(camera.xRot());
        float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw);
        float sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
        vector(16, sy * cp, -sp, cy * cp, (float) Math.tan(Math.toRadians(camera.getFov()) * 0.5));
        vector(32, -cy, 0, sy, (float) width / height);
        vector(48, sy * sp, cp, cy * sp, settings.steps());
        vector(64, settings.getThickness(), settings.getCoverage(), settings.getDensity(), size);
        float sunHeight = light.sunPosition()[1];
        boolean day = sunHeight >= -0.04f;
        float[] direction = day ? light.sunPosition() : light.moonPosition();
        float intensity = day ? 0.65f * LightManager.getSunIntensity() : 0.08f * LightManager.getMoonIntensity();
        float rain = world.getRainLevel(delta);
        vector(80, direction[0], direction[1], direction[2], intensity * (1 - 0.65f * rain));
        vector(96, ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, rain);
        vector(112, sunHeight, (color >>> 24) / 255f, world.getMinY(), 0);
    }

    static float periodic(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / 131072.0) * 131072.0);
    }

    private void vector(int offset, float x, float y, float z, float w) {
        parameters.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, z).putFloat(offset + 12, w);
    }

    static String loadShader() {
        try (InputStream input = VolumetricCloudRenderer.class.getResourceAsStream(SHADER_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing " + SHADER_RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read volumetric cloud shader", failure);
        }
    }

    @Override public void close() {
        if (resources != null) {
            var encoder = RemixVulkanSurface.from(Minecraft.getInstance().windowSurface()).device().createCommandEncoder();
            encoder.queueForDestroy(resources);
            resources = null;
        }
        if (outputView != null) outputView.close();
        if (output != null) output.close();
        outputView = null;
        output = null;
    }
}
