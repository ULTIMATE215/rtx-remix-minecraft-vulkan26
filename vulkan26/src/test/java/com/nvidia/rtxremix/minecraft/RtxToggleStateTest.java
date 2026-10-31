/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.mixin.SectionBuilderMixin;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth-table coverage for the master/sub-flag "active" predicates.
 * Capture requires a ready renderer, the master request, and the relevant
 * sub-flag. Startup failure must leave vanilla section compilation intact.
 */
class RtxToggleStateTest {
    private RemixRenderer previousRenderer;

    @BeforeEach
    void installReadyRenderer() throws Exception {
        previousRenderer = RtxRemixMod.renderer;
        RtxRemixMod.renderer = new RemixRenderer();
        setRendererInitialized(true);
    }

    @AfterEach
    void resetFlags() throws Exception {
        RtxRemixMod.renderer = previousRenderer;
        setFlag("rtxEnabled", true);
        setFlag("worldEnabled", true);
        setFlag("entitiesEnabled", true);
        setFlag("skyEnabled", true);
        setFlag("blockEntitiesEnabled", true);
        setFlag("particlesEnabled", true);
    }

    @Test
    void everyActivePredicateIsFalseWhenTheMasterToggleIsOff() throws Exception {
        setFlag("rtxEnabled", false);
        // All sub-flags remain true (defaults), but master off => nothing active.
        assertFalse(RtxRemixMod.isWorldActive(), "world");
        assertFalse(RtxRemixMod.isEntitiesActive(), "entities");
        assertFalse(RtxRemixMod.isBlockEntitiesActive(), "blockEntities");
        assertFalse(RtxRemixMod.isSkyActive(), "sky");
        assertFalse(RtxRemixMod.isParticlesActive(), "particles");
    }

    @Test
    void anActivePredicateIsTrueWhenTheMasterAndItsSubFlagAreOn() throws Exception {
        setFlag("rtxEnabled", true);
        assertTrue(RtxRemixMod.isWorldActive(), "world");
        assertTrue(RtxRemixMod.isEntitiesActive(), "entities");
        assertTrue(RtxRemixMod.isBlockEntitiesActive(), "blockEntities");
        assertTrue(RtxRemixMod.isSkyActive(), "sky");
        assertTrue(RtxRemixMod.isParticlesActive(), "particles");
    }

    @Test
    void eachActivePredicateIsolatesItsOwnSubFlagWhenTheMasterIsOn() throws Exception {
        setFlag("rtxEnabled", true);
        setFlag("entitiesEnabled", false);
        assertTrue(RtxRemixMod.isRtxEnabled());
        assertFalse(RtxRemixMod.isEntitiesActive(), "entities off");
        // A different sub-flag is unaffected.
        assertTrue(RtxRemixMod.isWorldActive(), "world still active");
    }

    @Test
    void noCaptureIsActiveWithoutARenderer() {
        RtxRemixMod.renderer = null;
        assertCaptureInactive();
    }

    @Test
    void sectionCompilerUsesVanillaWhenRendererFailedToInitialize() throws Exception {
        setRendererInitialized(false);
        assertCaptureInactive();
        assertFalse(VanillaSectionBuilds.anyInFlight());

        SectionCompiler.Results vanillaResult = new SectionCompiler.Results();
        AtomicInteger vanillaCalls = new AtomicInteger();
        Operation<SectionCompiler.Results> vanilla = arguments -> {
            assertEquals(4, arguments.length);
            assertTrue(VanillaSectionBuilds.anyInFlight(), "raster builds must retain light notifications");
            vanillaCalls.incrementAndGet();
            return vanillaResult;
        };
        var capture = SectionBuilderMixin.class.getDeclaredMethod("rtxremix$captureSection",
                SectionPos.class, RenderSectionRegion.class, VertexSorting.class,
                SectionBufferBuilderPack.class, Operation.class);
        capture.setAccessible(true);
        // The real wrapper must pass through without touching any Remix snapshot or emitter.
        Object result = capture.invoke(new SectionBuilderMixin() {}, null, null, null, null, vanilla);

        assertSame(vanillaResult, result);
        assertEquals(1, vanillaCalls.get());
        assertFalse(VanillaSectionBuilds.anyInFlight(), "the raster build must release its count");
    }

    @Test
    void requestingRtxBeforeInitializationDoesNotActivateCapture() throws Exception {
        setRendererInitialized(false);
        setFlag("rtxEnabled", false);

        RtxRemixMod.setRtxEnabled(true);
        assertCaptureInactive();

        // Readiness can later satisfy the request; failure must not erase the user's toggles.
        setRendererInitialized(true);
        assertTrue(RtxRemixMod.isRtxEnabled());
        assertTrue(RtxRemixMod.isWorldActive());
    }

    @Test
    void settingSkyEnabledKeepsBothGatesInSync() {
        RtxRemixMod.setSkyEnabled(false);
        assertFalse(RtxRemixMod.isSkyEnabled());
        assertFalse(SkyRenderer.isEnabled());

        RtxRemixMod.setSkyEnabled(true);
        assertTrue(RtxRemixMod.isSkyEnabled());
        assertTrue(SkyRenderer.isEnabled());
    }

    private static void setFlag(String name, boolean value) throws Exception {
        Field f = RtxRemixMod.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    private static void setRendererInitialized(boolean initialized) throws Exception {
        Field field = RemixRenderer.class.getDeclaredField("initialized");
        field.setAccessible(true);
        field.setBoolean(RtxRemixMod.renderer, initialized);
    }

    private static void assertCaptureInactive() {
        assertFalse(RtxRemixMod.isRtxEnabled(), "section and server capture gate");
        assertFalse(RtxRemixMod.isWorldActive(), "world");
        assertFalse(RtxRemixMod.isEntitiesActive(), "entities");
        assertFalse(RtxRemixMod.isBlockEntitiesActive(), "block entities");
        assertFalse(RtxRemixMod.isSkyActive(), "sky");
        assertFalse(RtxRemixMod.isParticlesActive(), "particles");
        assertFalse(RtxRemixMod.isRemixWorldRendering(), "vanilla draw suppression");
    }
}
