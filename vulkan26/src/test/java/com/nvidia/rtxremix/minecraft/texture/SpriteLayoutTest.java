/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpriteLayoutTest {
    @Test
    void anAnimationPatchAndTheInitialAtlasHaveTheSameEdgesAndCorners() {
        byte[] content = {1, 2, 3, 4};
        byte[] expected = {
                1, 1, 2, 2,
                1, 1, 2, 2,
                3, 3, 4, 4,
                3, 3, 4, 4
        };
        ByteBuffer patch = ByteBuffer.allocate(16);
        SpriteLayout.writePadded(patch, content, 2, 2, 1, 1);
        assertEquals(16, patch.position());
        assertArrayEquals(expected, patch.array());

        byte[] atlas = new byte[36];
        Arrays.fill(atlas, (byte) 99);
        atlas[14] = 1;
        atlas[15] = 2;
        atlas[20] = 3;
        atlas[21] = 4;
        SpriteLayout.pad(atlas, 6, 2, 2, 2, 2, 1, 1);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                byte value = x >= 1 && x <= 4 && y >= 1 && y <= 4
                        ? expected[(y - 1) * 4 + x - 1] : 99;
                assertEquals(value, atlas[y * 6 + x], "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void paddingCopiesWholeRgbaPixelsIncludingAlpha() {
        byte[] first = {1, 2, 3, 0};
        byte[] last = {4, 5, 6, (byte) 255};
        ByteBuffer patch = ByteBuffer.allocate(4 * 3 * 4);
        SpriteLayout.writePadded(patch, new byte[]{1, 2, 3, 0, 4, 5, 6, (byte) 255},
                2, 1, 4, 1);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 4; x++) {
                int offset = (y * 4 + x) * 4;
                assertArrayEquals(x < 2 ? first : last,
                        Arrays.copyOfRange(patch.array(), offset, offset + 4));
            }
        }
    }
}
