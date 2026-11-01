/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasAnimationForwarderTest {
    private static final Identifier SPRITE = Identifier.withDefaultNamespace("block/lava_still");

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
    }

    @Test
    void onlyTheCurrentStitchCanSupplyASpritesNativeMemory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TextureAtlasSprite stale = sprite(TextureAtlas.LOCATION_BLOCKS, fixture.contents);
            AtlasAnimationForwarder.init(Map.of(), fixture.atlas);

            assertFalse(AtlasAnimationForwarder.addFrame(stale, 2, 0, 1, 0));
            assertEquals(0, pendingPatches().size());
            assertTrue(AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 0, 1, 0));
            assertEquals(1, pendingPatches().size());
        }
    }

    @Test
    void anIdenticalSpriteIdInAnotherAtlasIsIgnored() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TextureAtlasSprite particle = sprite(TextureAtlas.LOCATION_PARTICLES, fixture.contents);
            AtlasAnimationForwarder.init(Map.of(), fixture.atlas);

            assertFalse(AtlasAnimationForwarder.addFrame(particle, 2, 0, 1, 0));
            assertEquals(0, pendingPatches().size());
        }
    }

    @Test
    void repeatedTicksCoalesceIntoTheLatestInterpolatedFrameIncludingAlpha() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.contents.image.setPixelABGR(0, 0, 0x503C2814);
            fixture.contents.image.setPixelABGR(1, 0, 0xA08C7864);
            AtlasAnimationForwarder.init(Map.of(), fixture.atlas);

            assertTrue(AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 0, 1, 0));
            assertTrue(AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 1, 0, 0.25f));
            assertEquals(1, pendingPatches().size());
            Object patch = pendingPatches().getFirst();
            var source = AtlasAnimationForwarder.class.getDeclaredMethod("source", patch.getClass());
            source.setAccessible(true);
            ByteBuffer pixels = (ByteBuffer) source.invoke(null, patch);
            assertArrayEquals(new byte[]{80, 100, 120, (byte) 140},
                    new byte[]{pixels.get(0), pixels.get(1), pixels.get(2), pixels.get(3)});
        }
    }

    @Test
    void pauseDiscardsQueuedFramesAndResumeRequestsFreshAnimationState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtlasAnimationForwarder.init(Map.of(), fixture.atlas);
            long firstGeneration = AtlasAnimationForwarder.generation();
            AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 0, 1, 0);
            AtlasAnimationForwarder.pause();

            assertEquals(0, AtlasAnimationForwarder.generation());
            assertEquals(0, pendingPatches().size());
            assertFalse(AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 1, 0, 0));
            AtlasAnimationForwarder.resume();
            assertTrue(AtlasAnimationForwarder.generation() > firstGeneration);
            assertEquals(2, fixture.atlas.captureRequests);
            assertTrue(AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 1, 0, 0));
            assertEquals(1, pendingPatches().size());
        }
    }

    @Test
    void onlyClosingTheActiveAtlasInvalidatesItsPendingMemory() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture unrelated = new Fixture()) {
            AtlasAnimationForwarder.init(Map.of(), fixture.atlas);
            AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 0, 1, 0);
            AtlasAnimationForwarder.onAtlasClosed(unrelated.atlas);
            assertTrue(AtlasAnimationForwarder.generation() > 0);
            assertEquals(1, pendingPatches().size());

            AtlasAnimationForwarder.onAtlasClosed(fixture.atlas);
            assertEquals(0, pendingPatches().size());
            AtlasAnimationForwarder.resume();
            assertEquals(0, AtlasAnimationForwarder.generation());
            assertFalse(AtlasAnimationForwarder.addFrame(fixture.sprite, 2, 0, 1, 0));
        }
    }

    private static List<?> pendingPatches() throws Exception {
        var field = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        field.setAccessible(true);
        return (List<?>) field.get(null);
    }

    private static TextureAtlasSprite sprite(Identifier atlas, Contents contents) {
        return new TextureAtlasSprite(atlas, contents, 16, 16, 2, 4, 1) {};
    }

    private static final class Fixture implements AutoCloseable {
        final Contents contents = new Contents(new NativeImage(2, 1, true));
        final TextureAtlasSprite sprite = sprite(TextureAtlas.LOCATION_BLOCKS, contents);
        final Atlas atlas = CpuTextureFixtures.withoutGpuConstructor(Atlas.class);

        Fixture() {
            atlas.sprites = new HashMap<>();
            atlas.sprites.put(SPRITE, sprite);
        }

        @Override
        public void close() {
            AtlasAnimationForwarder.onAtlasClosed(atlas);
            contents.close();
        }
    }

    private static final class Contents extends SpriteContents implements SpriteContentsAccessor {
        private final NativeImage image;

        Contents(NativeImage image) {
            super(SPRITE, new FrameSize(1, 1), image);
            this.image = image;
        }

        @Override public NativeImage getImage() { return image; }
        @Override public NativeImage[] getMipImages() { return new NativeImage[]{image}; }
        @Override public AnimatedTexture getAnimation() { return null; }
    }

    private static final class Atlas extends TextureAtlas implements SpriteAtlasAccessor, AtlasAnimationSource {
        private Map<Identifier, TextureAtlasSprite> sprites;
        private int captureRequests;

        // Bypassed by the CPU fixture: the vanilla constructor requires a live GPU device.
        private Atlas() { super(TextureAtlas.LOCATION_BLOCKS); }

        @Override public Map<Identifier, TextureAtlasSprite> getSprites() { return sprites; }
        @Override public int getWidth() { return 16; }
        @Override public int getHeight() { return 16; }
        @Override public void rtxremix$captureAnimations() { captureRequests++; }
    }
}
