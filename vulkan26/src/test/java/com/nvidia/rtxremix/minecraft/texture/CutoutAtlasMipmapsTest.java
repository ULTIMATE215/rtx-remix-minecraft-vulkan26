/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CutoutAtlasMipmapsTest {
    @ParameterizedTest
    @ValueSource(strings = {"oak", "birch", "spruce"})
    void ddsPreservesVanillaLeafColorsAlphaAndGutters(String tree, @TempDir Path directory) throws Exception {
        NativeImage[] images = leaves(tree);
        try {
            var sprite = CutoutAtlasMipmaps.Sprite.capture(16, 16, 16, 16, 16, images, null);
            byte[][] atlas = CutoutAtlasMipmaps.build(64, 64, List.of(sprite));
            assertEquals(5, atlas.length);
            Path dds = DdsTextureCache.write(directory, 64, 64, atlas);
            ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(dds)).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x20534444, data.getInt(0));
            assertEquals(124, data.getInt(4));
            assertEquals(64, data.getInt(12));
            assertEquals(64, data.getInt(16));
            assertEquals(256, data.getInt(20));
            assertEquals(5, data.getInt(28));
            assertEquals(0x30315844, data.getInt(84));
            assertEquals(28, data.getInt(128));
            assertEquals(3, data.getInt(132));
            assertEquals(1, data.getInt(140));

            int offset = 148;
            for (int level = 0; level < images.length; level++) {
                int width = 64 >> level;
                int size = 16 >> level;
                NativeImage source = images[level];
                for (int y = 0; y < size * 3; y++) {
                    for (int x = 0; x < size * 3; x++) {
                        int expected = NativeImagePixels.abgr(source,
                                Math.clamp(x - size, 0, size - 1), Math.clamp(y - size, 0, size - 1));
                        assertEquals(expected, data.getInt(offset + (y * width + x) * 4),
                                tree + " mip " + level + " at " + x + "," + y);
                    }
                }
                assertEquals(0, data.getInt(offset + (width * width - 1) * 4), "unused atlas area");
                offset += width * width * 4;
            }
            assertEquals(data.limit(), offset);

            // A plain average does not encode Minecraft's cutout coverage correction.
            boolean differentAlpha = false;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int sum = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            sum += images[0].getPixel(2 * x + dx, 2 * y + dy) >>> 24;
                        }
                    }
                    differentAlpha |= (images[1].getPixel(x, y) >>> 24) != sum / 4;
                }
            }
            assertTrue(differentAlpha, "The fixture must exercise the coverage lost by a mip-0-only upload");
        } finally {
            for (NativeImage image : images) image.close();
        }
    }

    @Test
    void labPbrAoSurvivesAtEveryMipWithoutChangingCutoutAlpha() throws Exception {
        NativeImage[] images = leaves("oak");
        try (NativeImage normal = new NativeImage(1, 1, false)) {
            normal.setPixel(0, 0, 0xff808060); // Blue encodes AO, independently of red and green.
            var sprite = CutoutAtlasMipmaps.Sprite.capture(16, 16, 16, 16, 16, images, normal);
            byte[][] atlas = CutoutAtlasMipmaps.build(64, 64, List.of(sprite));
            for (int level = 0; level < images.length; level++) {
                int size = 16 >> level;
                int width = 64 >> level;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int original = NativeImagePixels.abgr(images[level], x, y);
                        int offset = ((size + y) * width + size + x) * 4;
                        for (int c = 0; c < 3; c++) {
                            assertEquals((original >>> (c * 8) & 255) * 96 / 255,
                                    atlas[level][offset + c] & 255);
                        }
                        assertEquals(original >>> 24, atlas[level][offset + 3] & 255);
                    }
                }
            }
        } finally {
            for (NativeImage image : images) image.close();
        }
    }

    @Test
    void neighboringSpritesKeepTheirOwnMipColorsAndAo() throws Exception {
        NativeImage[] first = leaves("oak");
        NativeImage[] second = leaves("birch");
        try (NativeImage normal = new NativeImage(2, 2, false)) {
            normal.setPixel(0, 0, 0xff000000);
            normal.setPixel(1, 0, 0xff000040);
            normal.setPixel(0, 1, 0xff000080);
            normal.setPixel(1, 1, 0xff0000c0);
            var a = CutoutAtlasMipmaps.Sprite.capture(16, 16, 16, 16, 16, first, normal);
            var b = CutoutAtlasMipmaps.Sprite.capture(80, 16, 16, 16, 16, second, null);
            byte[] originalAo = a.ao().clone();
            byte[][] atlas = CutoutAtlasMipmaps.build(128, 64, List.of(a, b));
            for (int level = 1; level < atlas.length; level++) {
                int width = 128 >> level;
                int firstOffset = ((16 >> level) * width + (16 >> level)) * 4;
                int secondOffset = ((16 >> level) * width + (80 >> level)) * 4;
                assertEquals((first[level].getPixel(0, 0) >> 16 & 255) * 96 / 255,
                        atlas[level][firstOffset] & 255);
                assertEquals(NativeImagePixels.abgr(second[level], 0, 0),
                        ByteBuffer.wrap(atlas[level]).order(ByteOrder.LITTLE_ENDIAN).getInt(secondOffset));
            }
            assertArrayEquals(originalAo, a.ao(), "Borrowed source data must remain untouched");
        } finally {
            for (NativeImage image : first) image.close();
            for (NativeImage image : second) image.close();
        }
    }

    @Test
    void mipChainStopsBeforeSpriteBoundariesWouldOverlap() throws Exception {
        NativeImage[] images = leaves("oak");
        try {
            var sprite = CutoutAtlasMipmaps.Sprite.capture(2, 2, 16, 16, 2, images, null);
            byte[][] atlas = CutoutAtlasMipmaps.build(32, 32, List.of(sprite));
            assertEquals(2, atlas.length);
        } finally {
            Arrays.stream(images).forEach(NativeImage::close);
        }
    }

    private static NativeImage[] leaves(String tree) throws Exception {
        String path = "/assets/minecraft/textures/block/" + tree + "_leaves.png";
        try (var input = CutoutAtlasMipmapsTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "Minecraft dependency must supply " + path);
            NativeImage image = NativeImage.read(input);
            return MipmapGenerator.generateMipLevels(Identifier.withDefaultNamespace("block/" + tree + "_leaves"),
                    new NativeImage[]{image}, 4, MipmapStrategy.DARK_CUTOUT, 0, Transparency.TRANSPARENT);
        }
    }
}
