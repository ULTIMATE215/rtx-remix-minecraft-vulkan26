/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModelPart;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Runs Fabric's real vanilla-model encoder against the terrain capture adapter. */
class FabricBlockModelCaptureTest {
    private SpriteContents contents;
    private TextureAtlasSprite sprite;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void createSprite() {
        contents = new SpriteContents(Identifier.withDefaultNamespace("block/test"),
                new FrameSize(8, 8), new NativeImage(8, 8, true));
        sprite = new TextureAtlasSprite(Identifier.withDefaultNamespace("textures/atlas/blocks.png"),
                contents, 64, 64, 16, 32, 0) {};
    }

    @AfterEach
    void closeSprite() {
        contents.close();
    }

    @Test
    void enclosedBlockStillEmitsTheUnculledGroup() throws Exception {
        assertEquals(Collections.singletonList(null), emitPart(EnumSet.noneOf(Direction.class), true));
    }

    @Test
    void exposedFacesKeepTheirCullDirectionThroughFabricEncoding() throws Exception {
        for (Direction face : Direction.values()) {
            assertEquals(Arrays.asList(face, null), emitPart(EnumSet.of(face), true), face.toString());
        }
    }

    @Test
    void anEmptyUnculledGroupDoesNotAbortDirectionalEmission() throws Exception {
        assertEquals(Arrays.asList(Direction.values()), emitPart(EnumSet.allOf(Direction.class), false));
    }

    @Test
    void bakedQuadConversionPreservesPropertiesNotSuppliedByTheModel() {
        RemixRenderContext context = new RemixRenderContext();
        context.clear();
        context.cullFace(Direction.EAST).ambientOcclusion(TriState.FALSE)
                .shadeMode(ShadeMode.VANILLA).tag(42);

        context.fromBakedQuad(quad(0));

        assertEquals(Direction.EAST, context.cullFace());
        assertEquals(TriState.FALSE, context.ambientOcclusion());
        assertEquals(ShadeMode.VANILLA, context.shadeMode());
        assertEquals(42, context.tag());
    }

    @Test
    void bakedQuadConversionReplacesVertexDataAndMaterialLighting() {
        RemixRenderContext context = new RemixRenderContext();
        context.clear();
        for (int emission : new int[] {15, 7, 0}) {
            for (int vertex = 0; vertex < 4; vertex++) {
                context.pos(vertex, 9, 9, 9).uv(vertex, 9, 9)
                        .color(vertex, 0x12345678).normal(vertex, 1, 0, 0).lightmap(vertex, -1);
            }
            BakedQuad quad = quad(emission);

            context.fromBakedQuad(quad);

            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(quad.position(vertex), context.copyPos(vertex, null));
                assertEquals(UVPair.unpackU(quad.packedUV(vertex)), context.u(vertex));
                assertEquals(UVPair.unpackV(quad.packedUV(vertex)), context.v(vertex));
                assertEquals(-1, context.color(vertex));
                assertFalse(context.hasNormal(vertex));
                assertEquals(LightCoordsUtil.pack(emission, emission), context.lightmap(vertex));
            }
            assertEquals(Direction.UP, context.nominalFace());
            assertEquals(ChunkSectionLayer.SOLID, context.chunkLayer());
            assertEquals(emission == 15, context.emissive());
            assertSame(sprite, context.bakedSprite());
        }
    }

    private List<Direction> emitPart(EnumSet<Direction> exposed, boolean unculled) throws Exception {
        var constructor = BlockQuadEmitter.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BlockQuadEmitter capture = constructor.newInstance();
        var stateField = BlockQuadEmitter.class.getDeclaredField("frapiState");
        stateField.setAccessible(true);
        stateField.set(capture, Blocks.STONE.defaultBlockState());
        var worldField = BlockQuadEmitter.class.getDeclaredField("frapiWorld");
        worldField.setAccessible(true);
        worldField.set(capture, Proxy.newProxyInstance(BlockAndTintGetter.class.getClassLoader(),
                new Class<?>[] {BlockAndTintGetter.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getBlockState")) {
                        throw new AssertionError("Unexpected world access: " + method);
                    }
                    BlockPos pos = (BlockPos) args[0];
                    for (Direction face : exposed) {
                        if (pos.getX() == face.getStepX() && pos.getY() == face.getStepY()
                                && pos.getZ() == face.getStepZ()) {
                            return Blocks.AIR.defaultBlockState();
                        }
                    }
                    return Blocks.STONE.defaultBlockState();
                }));
        var predicateField = BlockQuadEmitter.class.getDeclaredField("frapiCullPredicate");
        predicateField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Predicate<Direction> cullTest = (Predicate<Direction>) predicateField.get(capture);

        List<Direction> emitted = new ArrayList<>();
        RemixRenderContext context = new RemixRenderContext();
        context.prepare(cullTest, result -> emitted.add(result.cullFace()));
        new TestPart(quad(0), unculled).emitQuads(context, cullTest);
        return emitted;
    }

    private BakedQuad quad(int emission) {
        return new BakedQuad(new Vector3f(0, 1, 0), new Vector3f(0, 1, 1),
                new Vector3f(1, 1, 1), new Vector3f(1, 1, 0),
                UVPair.pack(sprite.getU0(), sprite.getV0()), UVPair.pack(sprite.getU0(), sprite.getV1()),
                UVPair.pack(sprite.getU1(), sprite.getV1()), UVPair.pack(sprite.getU1(), sprite.getV0()),
                Direction.UP, new BakedQuad.MaterialInfo(sprite, ChunkSectionLayer.SOLID, null, -1, true, emission));
    }

    private record TestPart(BakedQuad quad, boolean unculled)
            implements BlockStateModelPart, FabricBlockStateModelPart {
        @Override public List<BakedQuad> getQuads(Direction face) {
            return face == null && !unculled ? List.of() : List.of(quad);
        }
        @Override public boolean useAmbientOcclusion() { return true; }
        @Override public Material.Baked particleMaterial() {
            return new Material.Baked(quad.materialInfo().sprite(), false);
        }
        @Override public int materialFlags() { return 0; }
    }
}
