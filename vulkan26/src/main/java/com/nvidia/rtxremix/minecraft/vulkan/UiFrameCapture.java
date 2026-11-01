/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in paired screenshots locate HUD trails before or after GUI rendering, without a GPU stall. */
public final class UiFrameCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(UiFrameCapture.class);
    private static final UiFrameCapture INSTANCE = new UiFrameCapture();
    private final AtomicInteger pendingImages = new AtomicInteger();
    private ClientLevel world;
    private Path directory, currentPair;
    private int remaining, index, delayFrames;
    private long startAt;

    private UiFrameCapture() {}

    public static Path request(Path screenshots) throws IOException {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        if (!RtxRemixMod.isRtxEnabled() || client.level == null) throw new IllegalStateException("RTX world is not active");
        if (INSTANCE.remaining != 0 || INSTANCE.pendingImages.get() != 0) throw new IllegalStateException("UI capture is still in progress");
        Path directory = screenshots.resolve("rtx-ui-" + System.currentTimeMillis());
        Files.createDirectories(directory);
        var api = RtxRemixMod.renderer.getRemixApi();
        StringBuilder modes = new StringBuilder("Images are paired before/after Minecraft's GUI, before native Present.\n");
        for (String key : new String[]{"rtx.upscalerType", "rtx.enableRayReconstruction", "rtx.qualityDLSS",
                "rtx.dlfg.enable", "rtx.sparseRendering.enableSparseRendering", "rtx.sparseRendering.quality"}) {
            modes.append(key).append(" = ").append(api.getConfigVariableResolved(key)).append('\n');
        }
        Files.writeString(directory.resolve("render-modes.txt"),modes);
        INSTANCE.world = client.level;
        INSTANCE.directory = directory;
        INSTANCE.index = 0;
        INSTANCE.remaining = 8;
        INSTANCE.delayFrames = 0;
        INSTANCE.startAt = System.nanoTime() + 2_000_000_000L;
        return directory;
    }

    public static void beforeGui() { INSTANCE.beginFrame(); }
    public static void afterGui() { INSTANCE.endFrame(); }

    private void beginFrame() {
        if (remaining <= 0) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != world || !RtxRemixMod.isRtxEnabled()) {
            remaining = 0;
            world = null;
            currentPair = null;
            return;
        }
        if (System.nanoTime() < startAt || client.gui.screen() != null || pendingImages.get() != 0) return;
        if (delayFrames-- > 0) return;
        delayFrames = 4;
        currentPair = directory.resolve(String.format(java.util.Locale.ROOT,"%02d",++index));
        if (!capture(Path.of(currentPair + "-before-ui.png"))) currentPair = null;
    }

    private void endFrame() {
        if (currentPair == null) return;
        capture(Path.of(currentPair + "-after-ui.png"));
        currentPair = null;
        remaining = Math.max(0,remaining - 1);
        if (remaining == 0) {
            world = null;
            LOGGER.info("UI frame comparisons recorded to {}",directory);
        }
    }

    private boolean capture(Path path) {
        pendingImages.incrementAndGet();
        try {
            Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image ->
                    CompletableFuture.runAsync(() -> write(image,path)));
            return true;
        } catch (RuntimeException failure) {
            pendingImages.decrementAndGet();
            remaining = 0;
            LOGGER.error("Cannot record UI frame comparison",failure);
            return false;
        }
    }

    private void write(NativeImage image, Path path) {
        try (image) {
            image.writeToFile(path);
        } catch (IOException failure) {
            LOGGER.error("Cannot save UI comparison {}",path,failure);
        } finally {
            pendingImages.decrementAndGet();
        }
    }
}
