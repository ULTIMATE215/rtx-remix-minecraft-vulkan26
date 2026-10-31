/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Allocation-free packing of 26.2 quad geometry for the retained mesh digest. */
public final class BakedQuadGeometry {
    private BakedQuadGeometry() {}

    /** The caller owns and reuses a 32-element destination; tint remains instance data. */
    public static int[] write(BakedQuad quad, int[] destination) {
        var direction = quad.direction();
        int normal = (direction.getStepX() * 127 & 255)
                | (direction.getStepY() * 127 & 255) << 8
                | (direction.getStepZ() * 127 & 255) << 16;
        for (int vertex = 0; vertex < 4; vertex++) {
            var position = quad.position(vertex);
            long uv = quad.packedUV(vertex);
            int offset = vertex * 8;
            destination[offset] = Float.floatToRawIntBits(position.x());
            destination[offset + 1] = Float.floatToRawIntBits(position.y());
            destination[offset + 2] = Float.floatToRawIntBits(position.z());
            destination[offset + 3] = -1;
            destination[offset + 4] = Float.floatToRawIntBits(UVPair.unpackU(uv));
            destination[offset + 5] = Float.floatToRawIntBits(UVPair.unpackV(uv));
            destination[offset + 6] = 0;
            destination[offset + 7] = normal;
        }
        return destination;
    }
}
