/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.vulkan.RemixVulkanSurface;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/** The parent's atmospheric integration, dispatched and retired through Minecraft's Vulkan encoder. */
final class AtmosphericSkyGenerator {
    static final int WIDTH = 256;
    static final int HEIGHT = 128;
    static final int UPPER_HEIGHT = HEIGHT / 2;
    static final int OUTPUT_BYTES = WIDTH * HEIGHT * 4 * Short.BYTES;
    static final int READBACK_BYTES = WIDTH * (HEIGHT + 1) * 4 * Short.BYTES;
    static final int LUT_WIDTH = 1024;
    static final int LUT_HEIGHT = 256;
    private static final int READBACK_RING_SIZE = 3;
    private static final String SHADER_RESOURCE = "/assets/rtx-remix-renderer/shaders/sky_atmosphere.comp";

    private final float[] sunDirection = new float[3];
    private final float[] moonDirection = new float[3];
    private final int width, upperHeight, outputBytes;
    private final VulkanCommandEncoder encoder;
    private final AtmosphericSkyResources resources;
    private final GpuFence[] readbackFences = new GpuFence[READBACK_RING_SIZE];
    private final long[] readbackGenerations = new long[READBACK_RING_SIZE];
    private final ByteBuffer skyParameters = BufferUtils.createByteBuffer(80);
    private final ByteBuffer publishedSkyParameters = BufferUtils.createByteBuffer(80);
    // A consumed ring slot can immediately be reused by the GPU. Return an independent
    // CPU snapshot whose lifetime extends through the caller's native texture upload.
    private final ByteBuffer output;
    private int readbackWriteIndex, readbackReadIndex, pendingReadbacks;
    private long generation;
    private boolean hasPublishedSkyParameters, initialized, closed;

    AtmosphericSkyGenerator() {
        this(WIDTH, HEIGHT);
    }

    AtmosphericSkyGenerator(int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (width < 8 || height < 4 || (height & 1) != 0) {
            throw new IllegalArgumentException("Sky resolution requires width >= 8 and an even height >= 4");
        }
        this.width = width;
        upperHeight = height / 2;
        outputBytes = width * height * 4 * Short.BYTES;
        output = BufferUtils.createByteBuffer(outputBytes);
        var device = RemixVulkanSurface.from(Minecraft.getInstance().windowSurface()).device();
        encoder = device.createCommandEncoder();
        resources = new AtmosphericSkyResources(device, loadShaderSource(width, height), width, height,
                width * (height + 1) * 4 * Short.BYTES);
    }

    /**
     * Returns the newest completed image without waiting for the GPU. Byte-identical
     * parameters skip dispatch, while pending readbacks still drain. Invalidating the
     * caller's output forces a dispatch even in a dimension whose clock never advances.
     */
    ByteBuffer generate(SkyLightState lightState, float[] upperMean,
                        float mieScatteringAnisotropy, boolean publishedOutputValid) {
        requireOpen();
        if (upperMean.length < 3) throw new IllegalArgumentException("Upper-mean output requires three floats");
        if (!Float.isFinite(mieScatteringAnisotropy)
                || mieScatteringAnisotropy < 0.0f || mieScatteringAnisotropy >= 1.0f) {
            throw new IllegalArgumentException("Mie scattering anisotropy must be in [0, 1)");
        }
        boolean parametersChanged;
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Parameters")) {
            setSkyTextureDirection(sunDirection, lightState.sunPosition());
            setSkyTextureDirection(moonDirection, lightState.moonPosition());
            normalize(sunDirection);
            normalize(moonDirection);
            parametersChanged = fillSkyParameters(lightState, mieScatteringAnisotropy);
        }
        ByteBuffer completed;
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Readback")) {
            completed = consumeCompletedReadback(upperMean);
        }
        if ((!parametersChanged && publishedOutputValid) || pendingReadbacks == READBACK_RING_SIZE) {
            return completed;
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.ProceduralGeneration.Dispatch")) {
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            beginCompute(commands);
            resources.dispatch(commands, skyParameters, 1, width, upperHeight);
            AtmosphericSkyResources.barrier(commands,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            resources.dispatch(commands, skyParameters, 2, 1, 1);
            resources.copyImage(commands, 3, resources.readbacks[readbackWriteIndex]);
            execute(commands);
            readbackFences[readbackWriteIndex] = encoder.createFence();
            readbackGenerations[readbackWriteIndex] = generation;
            readbackWriteIndex = (readbackWriteIndex + 1) % READBACK_RING_SIZE;
            pendingReadbacks++;
            markSkyParametersDispatched();
        }
        // The regular frame submission owns flushing this work; no additional queue or wait.
        return completed;
    }

    private void beginCompute(VkCommandBuffer commands) {
        if (!initialized) {
            resources.initialize(commands, skyParameters);
        } else {
            // Includes the previous image-to-buffer read before another compose writes the image.
            AtmosphericSkyResources.barrier(commands,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }
    }

    private void execute(VkCommandBuffer commands) {
        AtmosphericSkyResources.check(vkEndCommandBuffer(commands), "finish atmospheric sky commands");
        encoder.execute(commands);
        initialized = true;
    }

    private ByteBuffer consumeCompletedReadback(float[] upperMean) {
        int newest = -1;
        while (pendingReadbacks != 0) {
            GpuFence fence = readbackFences[readbackReadIndex];
            if (!fence.awaitCompletion(0)) break;
            fence.close();
            readbackFences[readbackReadIndex] = null;
            if (readbackGenerations[readbackReadIndex] == generation) newest = readbackReadIndex;
            readbackReadIndex = (readbackReadIndex + 1) % READBACK_RING_SIZE;
            pendingReadbacks--;
        }
        if (newest < 0) return null;
        ByteBuffer mapped = resources.readbacks[newest].mapped;
        upperMean[0] = Float.float16ToFloat(mapped.getShort(outputBytes));
        upperMean[1] = Float.float16ToFloat(mapped.getShort(outputBytes + Short.BYTES));
        upperMean[2] = Float.float16ToFloat(mapped.getShort(outputBytes + 2 * Short.BYTES));
        output.clear();
        MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), MemoryUtil.memAddress(output), outputBytes);
        return output;
    }

    /** Old world results are retired only after completion, but can never be published again. */
    void invalidate() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        generation++;
        hasPublishedSkyParameters = false;
    }

    /** Explicit diagnostic readback; the regular sky path never waits. */
    void readTransmittanceLut(ByteBuffer destination) {
        requireOpen();
        int bytes = LUT_WIDTH * LUT_HEIGHT * 4 * Float.BYTES;
        if (!destination.isDirect() || destination.capacity() < bytes) {
            throw new IllegalArgumentException("LUT readback requires a direct buffer of at least " + bytes + " bytes");
        }
        var readback = resources.new Readback(bytes);
        try {
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            beginCompute(commands);
            resources.copyImage(commands, 0, readback);
            execute(commands);
            try (GpuFence fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("Atmospheric LUT readback timed out");
                destination.clear().limit(bytes);
                MemoryUtil.memCopy(MemoryUtil.memAddress(readback.mapped), MemoryUtil.memAddress(destination), bytes);
            }
        } finally {
            encoder.queueForDestroy(readback);
        }
    }

    void destroy() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (GpuFence fence : readbackFences) if (fence != null) fence.close();
        encoder.queueForDestroy(resources);
        // Minecraft's encoder drains the final destruction queue before destroying its allocator.
    }

    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Atmospheric sky generator is closed");
    }

    private boolean fillSkyParameters(
            SkyLightState lightState,
            float mieScatteringAnisotropy) {
        putVectorAndScalar(
                0,
                sunDirection,
                lightState.sunAngularRadius());
        putVectorAndScalar(
                16,
                moonDirection,
                lightState.moonAngularRadius());
        putVectorAndScalar(
                32,
                lightState.sunSourceRadiance(),
                atmosphereScatteringScale(
                        lightState.sunPosition()[1]));
        putVectorAndScalar(
                48,
                lightState.moonSourceRadiance(),
                0.0f);
        skyParameters.putFloat(64, mieScatteringAnisotropy);
        skyParameters.putFloat(68, 0.0f);
        skyParameters.putFloat(72, 0.0f);
        skyParameters.putFloat(76, 0.0f);

        return !hasPublishedSkyParameters || skyParameters.mismatch(publishedSkyParameters) >= 0;
    }

    /** Records the block the dispatch just ran on, so the next identical block can skip it. */
    private void markSkyParametersDispatched() {
        publishedSkyParameters.put(0, skyParameters, 0, skyParameters.capacity());
        hasPublishedSkyParameters = true;
    }

    private void putVectorAndScalar(
            int offset,
            float[] vector,
            float scalar) {
        skyParameters.putFloat(offset, vector[0]);
        skyParameters.putFloat(offset + Float.BYTES, vector[1]);
        skyParameters.putFloat(offset + 2 * Float.BYTES, vector[2]);
        skyParameters.putFloat(offset + 3 * Float.BYTES, scalar);
    }

    int outputBytes() {
        return outputBytes;
    }

    private static String loadShaderSource(int width, int height) {
        try (InputStream stream =
                AtmosphericSkyGenerator.class.getResourceAsStream(
                        SHADER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing atmospheric sky shader " + SHADER_RESOURCE);
            }
            String source = new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8);
            return withSkyDimensions(source, width, height);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read atmospheric sky shader "
                            + SHADER_RESOURCE,
                    e);
        }
    }

    /**
     * Inserts the size defines directly below the shader's {@code #version} directive.
     * GLSL requires that directive to precede every statement, and the file opens with
     * its licence header, so the defines cannot simply follow the first line.
     */
    private static String withSkyDimensions(String source, int width, int height) {
        int versionStart = source.indexOf("#version");
        if (versionStart < 0) {
            throw new IllegalStateException(
                    "Atmospheric sky shader has no #version directive");
        }
        int versionEnd = source.indexOf('\n', versionStart) + 1;
        return source.substring(0, versionEnd)
                + "#define SKY_WIDTH " + width + "\n"
                + "#define SKY_HEIGHT " + height + "\n"
                + source.substring(versionEnd);
    }

    private static void setSkyTextureDirection(
            float[] output,
            float[] direction) {
        output[0] = -direction[0];
        output[1] = direction[1];
        output[2] = direction[2];
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(
                vector[0] * vector[0]
                        + vector[1] * vector[1]
                        + vector[2] * vector[2]);
        if (length > 0.0f) {
            vector[0] /= length;
            vector[1] /= length;
            vector[2] /= length;
        }
    }

    private static float atmosphereScatteringScale(float sunHeight) {
        float nightFactor =
                1.0f - smoothstep(-0.15f, 0.05f, sunHeight);
        return 16.0f * (1.0f - nightFactor) + 70.0f * nightFactor;
    }

    private static float smoothstep(
            float edge0,
            float edge1,
            float value) {
        float amount = Math.max(
                0.0f,
                Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return amount * amount * (3.0f - 2.0f * amount);
    }

}
