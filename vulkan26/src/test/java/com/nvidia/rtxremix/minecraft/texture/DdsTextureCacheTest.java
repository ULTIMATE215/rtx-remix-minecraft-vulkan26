/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DdsTextureCacheTest {
    @Test
    void reloadReusesIdenticalMipsAndKeepsOldFilesAvailableForStreaming(@TempDir Path directory) throws Exception {
        byte[][] levels = {new byte[16], new byte[4]};
        Path first = DdsTextureCache.write(directory, 2, 2, levels);
        byte[] firstContents = Files.readAllBytes(first);
        assertEquals(first, DdsTextureCache.write(directory, 2, 2, levels));
        levels[1][3] = (byte) 255;
        Path changed = DdsTextureCache.write(directory, 2, 2, levels);
        assertNotEquals(first, changed, "Lower mips must participate in the cache identity");
        assertArrayEquals(firstContents, Files.readAllBytes(first));
        assertEquals(255, Files.readAllBytes(changed)[167] & 255);
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void aTruncatedCacheFileIsReplaced(@TempDir Path directory) throws Exception {
        byte[][] levels = {new byte[16], new byte[4]};
        Path file = DdsTextureCache.write(directory, 2, 2, levels);
        byte[] expected = Files.readAllBytes(file);
        Files.write(file, new byte[2]);
        assertEquals(file, DdsTextureCache.write(directory, 2, 2, levels));
        assertArrayEquals(expected, Files.readAllBytes(file));
    }

    @Test
    void invalidMipDimensionsDoNotPublishAFile(@TempDir Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> DdsTextureCache.write(directory, 4, 4, new byte[][]{new byte[64], new byte[4]}));
        assertThrows(IllegalArgumentException.class,
                () -> DdsTextureCache.write(directory, 1, 1, new byte[][]{new byte[4], new byte[4]}));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }
}
