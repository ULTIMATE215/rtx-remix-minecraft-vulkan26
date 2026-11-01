/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Proxy;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FluidTintTest {
    // The lava block named in the normal-world crash report.
    private static final BlockPos POS = new BlockPos(256, 41, -79);
    private SpriteContents contents;
    private Map<Fluid, FluidModel> models;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void bakeVanillaFluidModels() {
        contents = new SpriteContents(Identifier.withDefaultNamespace("block/fluid_tint_test"),
                new FrameSize(8, 8), new NativeImage(8, 8, true));
        TextureAtlasSprite sprite = new TextureAtlasSprite(
                Identifier.withDefaultNamespace("textures/atlas/blocks.png"), contents, 8, 8, 0, 0, 0) {};
        models = FluidStateModelSet.bake(new MaterialBaker(sprite) {
            @Override
            protected Material.Baked bake(Material material) {
                return new Material.Baked(sprite, material.forceTranslucent());
            }
        });
    }

    @AfterEach
    void closeSprite() {
        contents.close();
    }

    @Test
    void vanillaSourceAndFlowingLavaPreserveTheirTextureColorWithoutWorldTintReads() {
        for (Fluid fluid : new Fluid[]{Fluids.LAVA, Fluids.FLOWING_LAVA}) {
            FluidModel model = models.get(fluid);
            assertNull(model.tintSource(), "vanilla lava deliberately has no tint source");
            assertEquals(0xFFFFFFFF, FluidTint.color(model, noWorldReads(), POS));
        }
    }

    @Test
    void vanillaSourceAndFlowingWaterKeepTheirBiomeColors() {
        for (Fluid fluid : new Fluid[]{Fluids.WATER, Fluids.FLOWING_WATER}) {
            for (int biomeColor : new int[]{0xFF3F76E4, 0xFF617B64}) {
                BlockAndTintGetter world = (BlockAndTintGetter) Proxy.newProxyInstance(
                        BlockAndTintGetter.class.getClassLoader(), new Class<?>[]{BlockAndTintGetter.class},
                        (proxy, method, args) -> {
                            assertEquals(POS, args[0]);
                            return switch (method.getName()) {
                                case "getBlockState" -> fluid.defaultFluidState().createLegacyBlock();
                                case "getBlockTint" -> biomeColor;
                                default -> throw new AssertionError("Unexpected world access: " + method);
                            };
                        });
                assertEquals(biomeColor, FluidTint.color(models.get(fluid), world, POS));
            }
        }
    }

    @Test
    void aWaterModelWithoutTintAlsoUsesUnmodifiedTextureColor() {
        assertEquals(0xFFFFFFFF, FluidTint.color(withTint(models.get(Fluids.WATER), null), noWorldReads(), POS));
    }

    @Test
    void customTintReceivesTheActualWorldPositionAndBlockState() {
        BlockState state = Blocks.LAVA.defaultBlockState();
        BlockAndTintGetter world = (BlockAndTintGetter) Proxy.newProxyInstance(
                BlockAndTintGetter.class.getClassLoader(), new Class<?>[]{BlockAndTintGetter.class},
                (proxy, method, args) -> {
                    assertEquals("getBlockState", method.getName());
                    assertEquals(POS, args[0]);
                    return state;
                });
        BlockTintSource tint = new BlockTintSource() {
            @Override public int color(BlockState ignored) {
                throw new AssertionError("Fluid capture must use the in-world tint");
            }
            @Override public int colorInWorld(BlockState actualState, BlockAndTintGetter actualWorld, BlockPos pos) {
                assertSame(state, actualState);
                assertSame(world, actualWorld);
                assertEquals(POS, pos);
                return 0x80336699;
            }
        };
        assertEquals(0x80336699, FluidTint.color(withTint(models.get(Fluids.LAVA), tint), world, POS));
    }

    private static FluidModel withTint(FluidModel model, BlockTintSource tint) {
        return new FluidModel(model.layer(), model.stillMaterial(), model.flowingMaterial(),
                model.overlayMaterial(), tint);
    }

    private static BlockAndTintGetter noWorldReads() {
        return (BlockAndTintGetter) Proxy.newProxyInstance(BlockAndTintGetter.class.getClassLoader(),
                new Class<?>[]{BlockAndTintGetter.class}, (proxy, method, args) -> {
                    throw new AssertionError("Untinted fluid must not sample the world: " + method);
                });
    }
}
