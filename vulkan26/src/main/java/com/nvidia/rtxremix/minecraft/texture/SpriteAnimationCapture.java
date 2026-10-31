/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Render-thread access to the frame that vanilla actually draws into an atlas. */
public interface SpriteAnimationCapture {
    void rtxremix$bindSprite(TextureAtlasSprite sprite);
    void rtxremix$captureFrame();
}
