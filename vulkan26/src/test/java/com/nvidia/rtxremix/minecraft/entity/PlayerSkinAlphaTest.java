/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import static com.nvidia.rtxremix.minecraft.entity.RenderLayerUtils.SurfaceMode.CUTOUT;
import static com.nvidia.rtxremix.minecraft.entity.RenderLayerUtils.SurfaceMode.TRANSLUCENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerSkinAlphaTest {
    private static final int WHITE = 0xFFFFFFFF;
    private static final List<String> BODY =
            List.of("head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
    private static final List<String> OUTER =
            List.of("hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fractionalOuterSkinBlendsWhileTheRealVanillaBodyRemainsOpaque(boolean slim) {
        Map<String, ModelPart.Polygon[]> parts = playerParts(slim);
        for (int alpha : new int[]{1, 64, 128, 254}) {
            byte[] pixels = opaqueSkin(64);
            // The six outer-layer regions in Minecraft's 64x64 skin format, independent
            // of the classifier's bounds calculation. The actual model supplies its UVs.
            paintAlpha(pixels, 64, 32, 0, 64, 16, alpha);   // hat
            paintAlpha(pixels, 64, 16, 32, 40, 48, alpha);  // jacket
            paintAlpha(pixels, 64, 40, 32, 56, 48, alpha);  // right sleeve
            paintAlpha(pixels, 64, 0, 32, 16, 48, alpha);   // right pants
            paintAlpha(pixels, 64, 48, 48, 64, 64, alpha);  // left sleeve
            paintAlpha(pixels, 64, 0, 48, 16, 64, alpha);   // left pants
            PlayerSkinAlpha skin = PlayerSkinAlpha.fromRgba(pixels, 64, 64);

            for (String name : BODY) {
                assertEquals(CUTOUT, skin.surfaceMode(parts.get(name), null, WHITE), name);
            }
            for (String name : OUTER) {
                assertEquals(TRANSLUCENT, skin.surfaceMode(parts.get(name), null, WHITE), name);
            }
        }
    }

    @Test
    void binaryOuterSkinsKeepOpaqueCoverageInsteadOfMakingTheWholePlayerBlend() {
        byte[] pixels = opaqueSkin(64);
        for (int i = 3; i < pixels.length; i += 8) pixels[i] = 0;
        PlayerSkinAlpha skin = PlayerSkinAlpha.fromRgba(pixels, 64, 64);
        for (ModelPart.Polygon[] sides : playerParts(false).values()) {
            assertEquals(CUTOUT, skin.surfaceMode(sides, null, WHITE));
        }
    }

    @Test
    void fractionalAlphaInUnusedSkinPixelsDoesNotMakeAnyPartBlend() {
        byte[] pixels = opaqueSkin(64);
        paintAlpha(pixels, 64, 63, 0, 64, 1, 128); // outside the hat's top and bottom faces
        PlayerSkinAlpha skin = PlayerSkinAlpha.fromRgba(pixels, 64, 64);
        for (ModelPart.Polygon[] sides : playerParts(false).values()) {
            assertEquals(CUTOUT, skin.surfaceMode(sides, null, WHITE));
        }
    }

    @Test
    void highResolutionSkinsUseNormalizedModelUvs() {
        byte[] pixels = opaqueSkin(128);
        paintAlpha(pixels, 128, 64, 0, 128, 32, 128);
        PlayerSkinAlpha skin = PlayerSkinAlpha.fromRgba(pixels, 128, 128);
        Map<String, ModelPart.Polygon[]> parts = playerParts(false);
        assertEquals(CUTOUT, skin.surfaceMode(parts.get("head"), null, WHITE));
        assertEquals(TRANSLUCENT, skin.surfaceMode(parts.get("hat"), null, WHITE));
    }

    @Test
    void aSkinUploadReplacesTheMaskAndCuboidDecisions() {
        ModelPart.Polygon[] sleeve = playerParts(false).get("right_sleeve");
        byte[] pixels = opaqueSkin(64);
        // Include unused fractional alpha so the first snapshot has a per-cuboid cache.
        paintAlpha(pixels, 64, 63, 0, 64, 1, 128);
        PlayerSkinAlpha before = PlayerSkinAlpha.fromRgba(pixels, 64, 64);
        assertEquals(CUTOUT, before.surfaceMode(sleeve, null, WHITE));

        paintAlpha(pixels, 64, 40, 32, 56, 48, 128);
        PlayerSkinAlpha after = PlayerSkinAlpha.fromRgba(pixels, 64, 64);
        assertEquals(TRANSLUCENT, after.surfaceMode(sleeve, null, WHITE));
        assertEquals(CUTOUT, before.surfaceMode(sleeve, null, WHITE), "the previous mask is a snapshot");

        paintAlpha(pixels, 64, 40, 32, 56, 48, 255);
        PlayerSkinAlpha opaqueAgain = PlayerSkinAlpha.fromRgba(pixels, 64, 64);
        assertEquals(CUTOUT, opaqueAgain.surfaceMode(sleeve, null, WHITE));
    }

    @Test
    void anIntentionalEntityFadeStillBlendsTheBody() {
        PlayerSkinAlpha skin = PlayerSkinAlpha.fromRgba(opaqueSkin(64), 64, 64);
        ModelPart.Polygon[] body = playerParts(false).get("body");
        assertEquals(TRANSLUCENT, skin.surfaceMode(body, null, 0x80FFFFFF));
        assertEquals(CUTOUT, skin.surfaceMode(body, null, WHITE));
        assertEquals(TRANSLUCENT, skin.surfaceMode(null, null, WHITE),
                "unknown geometry may supply its own per-vertex alpha");
    }

    @Test
    void binarySkinsShareAConstantWithoutRetainingModelParts() {
        assertSame(PlayerSkinAlpha.fromRgba(opaqueSkin(64), 64, 64),
                PlayerSkinAlpha.fromRgba(opaqueSkin(128), 128, 128));
    }

    private static byte[] opaqueSkin(int size) {
        byte[] pixels = new byte[size * size * 4];
        Arrays.fill(pixels, (byte) 255);
        return pixels;
    }

    private static void paintAlpha(byte[] pixels, int width, int left, int top,
                                   int right, int bottom, int alpha) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) pixels[(y * width + x) * 4 + 3] = (byte) alpha;
        }
    }

    private static Map<String, ModelPart.Polygon[]> playerParts(boolean slim) {
        ModelPart root = LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64)
                .bakeRoot();
        Map<String, ModelPart.Polygon[]> parts = new LinkedHashMap<>();
        root.visit(new PoseStack(), (pose, path, index, cube) ->
                parts.put(path.substring(path.lastIndexOf('/') + 1), cube.polygons));
        assertEquals(12, parts.size());
        return parts;
    }
}
