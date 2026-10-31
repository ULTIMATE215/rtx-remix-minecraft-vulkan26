/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.minecraft.mixin.FeatureRenderDispatcherAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/**
 * Evaluates 26.2's submitted features with vanilla's CPU geometry builders.
 * The builders write to the current capture provider instead of the staging
 * buffer; neither uploads nor raster draws occur during this preparation.
 *
 * <p>Each pass has a separate dispatcher so its prepared groups cannot disturb
 * a pending vanilla frame. The borrowed Minecraft buffers are never mutated or
 * closed. Hand/item scopes may flush before they restore their capture metadata.
 */
public final class RemixFeatureCapture implements AutoCloseable {
    private final SubmitNodeStorage nodes = new SubmitNodeStorage();
    private FeatureRenderDispatcher dispatcher;
    private FeatureFrameContext frameContext;
    private RemixVertexConsumerProvider provider;
    private boolean preparing;

    public SubmitNodeCollector begin(RemixVertexConsumerProvider provider) {
        RenderSystem.assertOnRenderThread();
        this.provider = provider;
        EntityRenderContext.get().setFeatureCapture(this);
        return nodes;
    }

    RemixVertexConsumerProvider provider() {
        return provider;
    }

    public void flush() {
        RenderSystem.assertOnRenderThread();
        if (provider == null || preparing || nodes.getSubmitsPerOrder().isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        var renderer = client.gameRenderer;
        if (dispatcher == null) {
            dispatcher = new FeatureRenderDispatcher(renderer.renderBuffers(), client.getModelManager(),
                    client.getAtlasManager(), client.font, renderer.gameRenderState());
        }
        var models = client.getModelManager().getBlockStateModelSet();
        var lightmap = renderer.lightmap();
        if (frameContext == null || frameContext.blockStateModelSet() != models
                || frameContext.lightmap() != lightmap) {
            frameContext = new FeatureFrameContext(renderer.gameRenderState().optionsRenderState,
                    client.font, models, client.getBlockColors(), client.getTextureManager(),
                    client.getAtlasManager(), lightmap, renderer.renderBuffers().stagedVertexBuffer());
        }

        EntityRenderContext context = EntityRenderContext.get();
        RemixVertexConsumerProvider previous = context.vertexConsumers();
        context.setVertexConsumers(provider);
        preparing = true;
        FeatureRenderDispatcherAccessor access = (FeatureRenderDispatcherAccessor) dispatcher;
        try {
            access.rtxremix$prepareFrame(frameContext, nodes).close();
        } catch (RuntimeException | Error failure) {
            var frame = access.rtxremix$preparedFrame();
            if (((FeatureCaptureFrameAccess) frame).rtxremix$isOpen()) {
                try {
                    frame.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            nodes.getSubmitsPerOrder().clear();
            throw failure;
        } finally {
            preparing = false;
            context.setVertexConsumers(previous);
        }
    }

    /** Drop submissions from an aborted capture before the pooled provider is reused. */
    public void discard() {
        nodes.getSubmitsPerOrder().clear();
        provider = null;
    }

    @Override
    public void close() {
        discard();
        if (dispatcher != null) dispatcher.close();
        dispatcher = null;
        frameContext = null;
    }
}
