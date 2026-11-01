/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.terrain.capture.DirectQuadWriter;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CutoutTerrainTest {
    @Test
    void preservedAndAnimatedCutoutsSubmitDifferentAtlasMaterialsIncludingSss() {
        try (Contents still = new Contents("still", false); Contents animated = new Contents("animated", true)) {
            BlockTextureManager.SpriteEntry staticEntry = entry(still, 0);
            staticEntry.hasCutoutMipmaps = true;
            staticEntry.hasSSS = true;
            BlockTextureManager.SpriteEntry animatedEntry = entry(animated, 1);
            animatedEntry.hasSSS = true;
            var textures = new Textures(staticEntry, animatedEntry);
            DirectQuadWriter writer = new DirectQuadWriter();
            writer.beginSection();
            submit(writer, textures, staticEntry.id);
            submit(writer, textures, animatedEntry.id);
            SectionMeshData mesh = writer.buildMeshData();
            try {
                assertEquals(List.of("cutout_mipped_sss", "cutout_sss"),
                        mesh.surfaces().stream().map(SectionMeshData.Surface::bucketKey).sorted().toList());
                for (var surface : mesh.surfaces()) {
                    assertEquals(ChunkManager.CAT_CUTOUT, surface.category());
                    assertEquals(4, surface.vertexCount());
                }
            } finally {
                mesh.release();
                writer.releaseBuffers();
            }
        }
    }

    @Test
    void missingPreservedUploadStillDrawsCutoutTerrainFromTheDynamicAtlas() {
        try (Contents contents = new Contents("fallback", false)) {
            BlockTextureManager.SpriteEntry sprite = entry(contents, 0);
            var textures = new Textures(sprite);
            DirectQuadWriter writer = new DirectQuadWriter();
            writer.beginSection();
            submit(writer, textures, sprite.id);
            SectionMeshData mesh = writer.buildMeshData();
            try {
                assertEquals(1, mesh.surfaces().size());
                assertEquals("cutout", mesh.surfaces().getFirst().bucketKey());
                assertEquals(ChunkManager.CAT_CUTOUT, mesh.surfaces().getFirst().category());
            } finally {
                mesh.release();
                writer.releaseBuffers();
            }
        }
    }

    private static void submit(DirectQuadWriter writer, Textures textures, Identifier sprite) {
        writer.stageQuad(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1}, 0, 0, 1, 0xffffffff, sprite,
                0, 0, 1, 1, "cutout");
        writer.flushBlock(textures);
    }

    private static BlockTextureManager.SpriteEntry entry(Contents contents, int index) {
        TextureAtlasSprite sprite = new TextureAtlasSprite(BlockTextureManager.BLOCK_ATLAS_ID,
                contents, 2, 2, 0, 0, 0) {};
        return new BlockTextureManager.SpriteEntry(index, contents.name(), sprite);
    }

    private static final class Contents extends SpriteContents implements SpriteContentsAccessor {
        private final NativeImage image;
        private final boolean animated;

        Contents(String name, boolean animated) {
            this(name, animated, new NativeImage(2, 2, true));
        }

        private Contents(String name, boolean animated, NativeImage image) {
            super(Identifier.withDefaultNamespace("test/" + name), new FrameSize(2, 2), image);
            this.image = image;
            this.animated = animated;
            image.setPixel(0, 0, 0xffffffff);
        }

        @Override public boolean isAnimated() { return animated; }
        @Override public NativeImage getImage() { return image; }
        @Override public NativeImage[] getMipImages() { return new NativeImage[]{image}; }
        @Override public AnimatedTexture getAnimation() { return null; }
    }

    private static final class Textures extends BlockTextureManager {
        private final Map<Identifier, SpriteEntry> entries;
        private final RemixMaterial sss = new RemixMaterial(new MaterialInfo());

        Textures(SpriteEntry... entries) {
            super(null);
            this.entries = new java.util.HashMap<>();
            for (SpriteEntry entry : entries) this.entries.put(entry.id, entry);
        }

        @Override public SpriteEntry getSpriteById(Identifier id) { return entries.get(id); }
        @Override public RemixMaterial getAtlasMaterialSSS(int layer) { return sss; }
    }
}
