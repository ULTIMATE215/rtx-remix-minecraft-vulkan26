/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Copies vanilla's star geometry before its CPU buffer is uploaded and closed. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin implements WorldRendererStarAccess {
    @Unique private float[] rtxremix$stars;

    @WrapOperation(method = "buildStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;buildOrThrow()Lcom/mojang/blaze3d/vertex/MeshData;"))
    private MeshData rtxremix$captureStars(BufferBuilder builder, Operation<MeshData> original) {
        MeshData mesh = original.call(builder);
        MeshData.DrawState draw = mesh.drawState();
        VertexFormatElement position = draw.format().getElement("Position");
        if (position != null) {
            ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            float[] stars = new float[draw.vertexCount() * 3];
            int stride = draw.format().getVertexSize();
            for (int vertex = 0; vertex < draw.vertexCount(); vertex++) {
                int source = vertices.position() + vertex * stride + position.offset();
                stars[vertex * 3] = vertices.getFloat(source);
                stars[vertex * 3 + 1] = vertices.getFloat(source + Float.BYTES);
                stars[vertex * 3 + 2] = vertices.getFloat(source + 2 * Float.BYTES);
            }
            rtxremix$stars = stars;
        }
        return mesh;
    }

    @Override
    public float[] rtxremix$copyStarVertices() {
        return rtxremix$stars == null ? new float[0] : rtxremix$stars.clone();
    }
}
