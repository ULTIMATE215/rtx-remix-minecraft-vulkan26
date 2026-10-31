/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import sun.misc.Unsafe;

/** CPU fixtures for lifetime tests whose vanilla constructors now immediately allocate a GPU texture. */
final class CpuTextureFixtures {
    private static final Unsafe ALLOCATOR = allocator();

    private CpuTextureFixtures() {}

    static DynamicTexture dynamicTexture(NativeImage image) {
        DynamicTexture texture = withoutGpuConstructor(DynamicTexture.class);
        try {
            // setPixels closes its predecessor, which is absent in this CPU fixture.
            var pixels = DynamicTexture.class.getDeclaredField("pixels");
            pixels.setAccessible(true);
            pixels.set(texture, image);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not attach fixture pixels", e);
        }
        return texture;
    }

    /** Callers initialize the CPU fields they exercise; GPU fields stay null and are never used. */
    static <T> T withoutGpuConstructor(Class<T> type) {
        try {
            return type.cast(ALLOCATOR.allocateInstance(type));
        } catch (InstantiationException e) {
            throw new AssertionError("Could not create CPU fixture for " + type, e);
        }
    }

    private static Unsafe allocator() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not initialize CPU texture fixtures", e);
        }
    }
}
