/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable RGBA8 DDS files for the runtime's existing texture-file API. */
final class DdsTextureCache {
    private static final int HEADER_BYTES = 148;

    private DdsTextureCache() {}

    /** Keeps the file available for deferred native texture streaming and resource reloads. */
    static Path write(Path directory, int width, int height, byte[][] levels) throws IOException {
        if (width <= 0 || height <= 0 || levels.length == 0
                || levels.length > 32 - Integer.numberOfLeadingZeros(Math.max(width, height))) {
            throw new IllegalArgumentException("Invalid DDS dimensions or mip count");
        }
        long byteCount = HEADER_BYTES;
        for (int level = 0; level < levels.length; level++) {
            int expected = Math.multiplyExact(Math.multiplyExact(
                    Math.max(1, width >> level), Math.max(1, height >> level)), 4);
            if (levels[level].length != expected) {
                throw new IllegalArgumentException("Invalid DDS mip " + level);
            }
            byteCount += expected;
        }

        byte[] header = header(width, height, levels.length);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        digest.update(header);
        for (byte[] level : levels) digest.update(level);
        Path target = directory.resolve(HexFormat.of().formatHex(digest.digest()) + ".dds");
        Files.createDirectories(directory);
        if (Files.isRegularFile(target) && Files.size(target) == byteCount) return target;

        Path temporary = Files.createTempFile(directory, "cutout-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                output.write(header);
                for (byte[] level : levels) output.write(level);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    private static byte[] header(int width, int height, int mipCount) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, 0x20534444); // DDS magic.
        header.putInt(4, 124);
        header.putInt(8, 0x100f | (mipCount > 1 ? 0x20000 : 0)); // CAPS, HEIGHT, WIDTH, PITCH, PIXELFORMAT.
        header.putInt(12, height);
        header.putInt(16, width);
        header.putInt(20, Math.multiplyExact(width, 4));
        header.putInt(28, mipCount);
        header.putInt(76, 32); // DDS_PIXELFORMAT size.
        header.putInt(80, 4); // DDPF_FOURCC.
        header.putInt(84, 0x30315844); // DX10.
        header.putInt(108, 0x1000 | (mipCount > 1 ? 0x400008 : 0)); // TEXTURE, MIPMAP, COMPLEX.
        header.putInt(128, 28); // DXGI_FORMAT_R8G8B8A8_UNORM, matching the in-memory albedo.
        header.putInt(132, 3); // D3D10_RESOURCE_DIMENSION_TEXTURE2D.
        header.putInt(140, 1); // One array element.
        header.putInt(144, 1); // Straight alpha.
        return header.array();
    }
}
