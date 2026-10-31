// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;

/** Reads the ABGR channel layout used by the Remix texture converters. */
public final class NativeImagePixels {
    private NativeImagePixels() {}

    public static int abgr(NativeImage image, int x, int y) {
        int argb = image.getPixel(x, y);
        return (argb & 0xff00ff00) | ((argb >>> 16) & 0xff) | ((argb & 0xff) << 16);
    }
}
