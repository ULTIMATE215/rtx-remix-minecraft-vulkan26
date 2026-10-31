/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.mixin.RenderPipelinesAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Composites the parent's refractive rain and lightmapped snow through Minecraft's
 * Vulkan pipelines, comparing fragments with Remix's primary depth. Draws retain
 * CPU meshes only until the Remix output for the same frame is available.
 */
public final class RemixWeatherRenderer {
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final int MATRIX_BYTES = (2 * 16 + 4) * Float.BYTES;
    private static final Identifier RAIN_TEXTURE = Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final BindGroupLayout BINDINGS = BindGroupLayout.builder()
            .withUniform("WeatherMatrices", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2")
            .withSampler("RemixDepth").build();
    private static final RenderPipeline RAIN_PIPELINE = pipeline(false);
    private static final RenderPipeline SNOW_PIPELINE = pipeline(true);

    private final List<DeferredDraw> draws = new ArrayList<>(2);
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf inverseCameraRotation = new Quaternionf();
    private ByteBufferBuilder vertices;
    private GpuTextureView lightmap;
    private GpuBufferSlice fog;
    private boolean cameraReady;
    private int capturedQuadCount;
    private int lastDrawCount;
    private int lastQuadCount;

    private static RenderPipeline pipeline(boolean snow) {
        Identifier shader = Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "core/rain_refraction");
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID,
                        snow ? "pipeline/snow" : "pipeline/rain_refraction"))
                .withVertexShader(shader).withFragmentShader(shader).withBindGroupLayout(BINDINGS)
                .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(new BlendFunction(
                        BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO)))
                .withDepthStencilState(Optional.empty()).withCull(false);
        if (snow) builder.withShaderDefine("REMIX_SNOW");
        return builder.build();
    }

    public static void registerShaders() {
        RenderPipelinesAccessor.rtxremix$register(RAIN_PIPELINE);
        RenderPipelinesAccessor.rtxremix$register(SNOW_PIPELINE);
    }

    public void beginCaptureFrame(GpuTextureView lightmap) {
        closePendingDraws();
        this.lightmap = lightmap;
        fog = RenderSystem.getShaderFog();
        capturedQuadCount = 0;
        lastDrawCount = 0;
        lastQuadCount = 0;
    }

    /** The shared arena is cleared only after all of its previous meshes close. */
    public BufferBuilder beginQuads() {
        if (vertices == null) vertices = new ByteBufferBuilder(64 * 1024);
        return new BufferBuilder(vertices, PrimitiveTopology.QUADS, DefaultVertexFormat.PARTICLE);
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        camera.rotation().conjugate(inverseCameraRotation);
        modelView.rotation(inverseCameraRotation);
        // The runtime's primary depth and Vulkan fragments both use [0, 1].
        projection.identity().perspective((float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, FAR_PLANE, true);
        cameraReady = true;
    }

    public void capture(MeshData buffer, Identifier texture) {
        if (texture == null || buffer.drawState().format() != DefaultVertexFormat.PARTICLE
                || buffer.drawState().primitiveTopology() != PrimitiveTopology.QUADS) {
            buffer.close();
            throw new IllegalArgumentException("Weather capture requires textured particle quads");
        }
        capturedQuadCount += buffer.drawState().vertexCount() / 4;
        draws.add(new DeferredDraw(buffer, texture, texture.equals(RAIN_TEXTURE)));
    }

    public void render(GpuTextureView finalColor, GpuTextureView depth) {
        if (draws.isEmpty()) return;
        try {
            if (depth == null || finalColor == null || !cameraReady || lightmap == null || fog == null) {
                throw new IllegalStateException("Remix weather requires this frame's color, depth, lightmap and camera");
            }
            Minecraft client = Minecraft.getInstance();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            GpuBufferSlice matrices;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                matrices = encoder.transientMemory().uploadGpu(
                        Std140Builder.onStack(stack, MATRIX_BYTES).putMat4f(modelView).putMat4f(projection)
                                .putVec4(client.gameRenderer.mainRenderTarget().width,
                                        client.gameRenderer.mainRenderTarget().height, 0, 0).get(),
                        RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(),
                        GpuBuffer.USAGE_UNIFORM);
            }
            // Preserve the parent's paint order: refractive rain, then snow.
            renderDraws(true, finalColor, depth, matrices, client, encoder);
            renderDraws(false, finalColor, depth, matrices, client, encoder);
            lastQuadCount = capturedQuadCount;
        } finally {
            closePendingDraws();
        }
    }

    private void renderDraws(boolean rain, GpuTextureView finalColor, GpuTextureView depth,
                             GpuBufferSlice matrices, Minecraft client, CommandEncoder encoder) {
        for (int i = 0; i < draws.size(); i++) {
            DeferredDraw draw = draws.get(i);
            if (draw.rain != rain) continue;
            MeshData.DrawState state = draw.buffer.drawState();
            GpuBufferSlice upload = encoder.transientMemory().uploadGpu(draw.buffer.vertexBuffer(), 4, GpuBuffer.USAGE_VERTEX);
            var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indices = sequential.getBuffer(state.indexCount());
            var texture = client.getTextureManager().getTexture(draw.texture);
            try (RenderPass pass = encoder.createRenderPass(() -> "Remix weather",
                    client.gameRenderer.mainRenderTarget().getColorTextureView(), Optional.empty())) {
                pass.setPipeline(rain ? RAIN_PIPELINE : SNOW_PIPELINE);
                pass.setUniform("WeatherMatrices", matrices);
                pass.setUniform("Fog", fog);
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.bindTexture("Sampler1", finalColor, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.bindTexture("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.bindTexture("RemixDepth", depth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setVertexBuffer(0, upload);
                pass.setIndexBuffer(indices, sequential.type());
                pass.drawIndexed(state.indexCount(), 1, 0, 0, 0);
            }
            lastDrawCount++;
        }
    }

    private void closePendingDraws() {
        for (int i = 0; i < draws.size(); i++) draws.get(i).buffer.close();
        draws.clear();
        if (vertices != null) vertices.clear();
    }

    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastQuadCount() { return lastQuadCount; }

    public void cleanup() {
        closePendingDraws();
        if (vertices != null) vertices.close();
        vertices = null;
        lightmap = null;
        fog = null;
        cameraReady = false;
    }

    private record DeferredDraw(MeshData buffer, Identifier texture, boolean rain) {}
}
