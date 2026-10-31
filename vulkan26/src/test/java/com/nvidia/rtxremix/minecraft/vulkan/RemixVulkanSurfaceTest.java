/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises Minecraft's real acquire/blit/present validation without a GPU. */
class RemixVulkanSurfaceTest {
    @Test
    void shutdownCompletesAnAcquiredFrameBeforeClosingTheSurface() throws Exception {
        Frame frame = new Frame();
        frame.surface.acquireNextTexture();
        assertThrows(IllegalStateException.class, frame.surface::close);

        RemixVulkanSurface.completeAcquiredFrame(frame.surface, frame.encoder, frame.texture, false);
        assertFalse(frame.surface.isAcquired());
        frame.surface.close();

        assertEquals(List.of("acquire", "blit", "submit", "present", "close"), frame.calls);
    }

    @Test
    void shutdownSubmitsAnAlreadyBlittedFrameWithoutBlittingTwice() throws Exception {
        Frame frame = new Frame();
        frame.surface.acquireNextTexture();
        frame.surface.blitFromTexture(frame.encoder, frame.texture);

        RemixVulkanSurface.completeAcquiredFrame(frame.surface, frame.encoder, null, true);
        frame.surface.close();

        assertEquals(List.of("acquire", "blit", "submit", "present", "close"), frame.calls);
    }

    @Test
    void anIdleSurfaceDoesNotSubmitOrAcquireAnotherFrame() throws Exception {
        Frame frame = new Frame();
        RemixVulkanSurface.completeAcquiredFrame(frame.surface, null, null, false);
        frame.surface.close();

        assertEquals(List.of("close"), frame.calls);
    }

    @Test
    void aFailedSubmissionDoesNotPresentOrPretendTheFrameWasReleased() throws Exception {
        Frame frame = new Frame();
        frame.surface.acquireNextTexture();
        frame.failSubmission = true;

        assertThrows(IllegalStateException.class, () -> RemixVulkanSurface.completeAcquiredFrame(
                frame.surface, frame.encoder, frame.texture, false));
        assertTrue(frame.surface.isAcquired());
        assertEquals(List.of("acquire", "blit", "submit"), frame.calls);

        frame.failSubmission = false;
        RemixVulkanSurface.completeAcquiredFrame(frame.surface, frame.encoder, null, true);
        frame.surface.close();
        assertEquals(List.of("acquire", "blit", "submit", "submit", "present", "close"), frame.calls);
    }

    private static final class Frame implements GpuSurfaceBackend {
        final List<String> calls = new ArrayList<>();
        final GpuSurface surface = new GpuSurface(this);
        final CommandEncoder encoder;
        final GpuTextureView texture;
        boolean failSubmission;

        Frame() throws Exception {
            CommandEncoderBackend backend = (CommandEncoderBackend) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {CommandEncoderBackend.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("submit")) throw new AssertionError(method);
                        calls.add("submit");
                        if (failSubmission) throw new IllegalStateException("fixture submission failed");
                        return null;
                    });
            encoder = new CommandEncoder(null, null, backend);
            GpuTexture color = new GpuTexture(GpuTexture.USAGE_COPY_SRC, "frame",
                    GpuFormat.RGBA8_UNORM, 854, 480, 1, 1) {
                @Override public void close() {}
                @Override public boolean isClosed() { return false; }
            };
            texture = new GpuTextureView(color, 0, 1) {
                @Override public void close() {}
                @Override public boolean isClosed() { return false; }
            };
            surface.configure(new GpuSurface.Configuration(854, 480, GpuSurface.PresentMode.values()[0]));
        }

        @Override public void configure(GpuSurface.Configuration configuration) {}
        @Override public boolean isSuboptimal() { return false; }
        @Override public void acquireNextTexture() { calls.add("acquire"); }
        @Override public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView texture) {
            calls.add("blit");
        }
        @Override public void present() { calls.add("present"); }
        @Override public Collection<GpuSurface.PresentMode> supportedPresentModes() {
            return List.of(GpuSurface.PresentMode.values());
        }
        @Override public void close() { calls.add("close"); }
    }
}
