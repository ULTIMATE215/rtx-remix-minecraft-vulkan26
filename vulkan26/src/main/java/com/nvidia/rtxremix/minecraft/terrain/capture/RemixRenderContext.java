/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState.FoilType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reusable 26.2 Fabric quad emitter. Transformed quads go straight to the section
 * writer; no per-quad snapshots or intermediate mesh records are allocated.
 * One instance belongs to each terrain worker's BlockQuadEmitter.
 */
final class RemixRenderContext implements QuadEmitter {
    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final float[] normals = new float[12];
    private final boolean[] normalSet = new boolean[4];
    private final int[] colors = new int[4];
    private final int[] lightmaps = new int[4];
    private final List<QuadTransform> transforms = new ArrayList<>();
    private final Vector3f faceNormal = new Vector3f();
    private final Vector3f bufferPosition = new Vector3f();
    private final Vector3f bufferNormal = new Vector3f();
    private boolean normalDirty = true;
    private TextureAtlasSprite bakedSprite;
    private Predicate<Direction> cullTest;
    private Consumer<RemixRenderContext> sink;
    private Direction cullFaceValue;
    private Direction nominalFaceValue;
    private QuadAtlas atlasValue;
    private ChunkSectionLayer chunkLayerValue;
    private RenderType itemRenderTypeValue;
    private boolean emissiveValue;
    private boolean diffuseShadeValue;
    private TriState ambientOcclusionValue;
    private FoilType foilTypeValue;
    private ShadeMode shadeModeValue;
    private boolean animatedValue;
    private int tintIndexValue;
    private int tagValue;

    void prepare(Predicate<Direction> cullTest, Consumer<RemixRenderContext> sink) {
        this.cullTest = cullTest;
        this.sink = sink;
        transforms.clear();
        clear();
    }

    TextureAtlasSprite bakedSprite() { return bakedSprite; }

    @Override
    public QuadEmitter clear() {
        Arrays.fill(positions, 0);
        Arrays.fill(uvs, 0);
        Arrays.fill(normals, 0);
        Arrays.fill(normalSet, false);
        Arrays.fill(colors, -1);
        Arrays.fill(lightmaps, 0);
        bakedSprite = null;
        normalDirty = true;
        cullFaceValue = null;
        nominalFaceValue = null;
        atlasValue = QuadAtlas.BLOCK;
        chunkLayerValue = null;
        itemRenderTypeValue = null;
        emissiveValue = false;
        diffuseShadeValue = true;
        ambientOcclusionValue = TriState.DEFAULT;
        foilTypeValue = FoilType.NONE;
        shadeModeValue = ShadeMode.ENHANCED;
        animatedValue = false;
        tintIndexValue = -1;
        tagValue = 0;
        return this;
    }

    @Override
    public QuadEmitter emit() {
        try {
            for (int index = transforms.size() - 1; index >= 0; index--) {
                if (!transforms.get(index).transform(this)) return this;
            }
            if (cullFaceValue == null || cullTest == null || !cullTest.test(cullFaceValue)) {
                sink.accept(this);
            }
            return this;
        } finally {
            clear();
        }
    }

    @Override public void pushTransform(QuadTransform transform) { transforms.add(transform); }
    @Override public void popTransform() { transforms.removeLast(); }

    @Override
    public QuadEmitter pos(int vertex, float x, float y, float z) {
        int offset = vertex * 3;
        positions[offset] = x;
        positions[offset + 1] = y;
        positions[offset + 2] = z;
        normalDirty = true;
        return this;
    }

    @Override public QuadEmitter color(int vertex, int color) { colors[vertex] = color; return this; }
    @Override public QuadEmitter lightmap(int vertex, int light) { lightmaps[vertex] = light; return this; }

    @Override
    public QuadEmitter uv(int vertex, float u, float v) {
        uvs[vertex * 2] = u;
        uvs[vertex * 2 + 1] = v;
        bakedSprite = null;
        return this;
    }

    @Override
    public QuadEmitter normal(int vertex, float x, float y, float z) {
        int offset = vertex * 3;
        normals[offset] = x;
        normals[offset + 1] = y;
        normals[offset + 2] = z;
        normalSet[vertex] = true;
        return this;
    }

    @Override public Direction cullFace() { return cullFaceValue; }
    @Override public QuadEmitter cullFace(Direction value) { cullFaceValue = value; return this; }
    @Override public Direction nominalFace() { return nominalFaceValue; }
    @Override public QuadEmitter nominalFace(Direction value) { nominalFaceValue = value; return this; }
    @Override public QuadAtlas atlas() { return atlasValue; }
    @Override public QuadEmitter atlas(QuadAtlas value) { atlasValue = value; bakedSprite = null; return this; }
    @Override public ChunkSectionLayer chunkLayer() { return chunkLayerValue; }
    @Override public QuadEmitter chunkLayer(ChunkSectionLayer value) { chunkLayerValue = value; return this; }
    @Override public RenderType itemRenderType() { return itemRenderTypeValue; }
    @Override public QuadEmitter itemRenderType(RenderType value) { itemRenderTypeValue = value; return this; }
    @Override public boolean emissive() { return emissiveValue; }
    @Override public QuadEmitter emissive(boolean value) { emissiveValue = value; return this; }
    @Override public boolean diffuseShade() { return diffuseShadeValue; }
    @Override public QuadEmitter diffuseShade(boolean value) { diffuseShadeValue = value; return this; }
    @Override public TriState ambientOcclusion() { return ambientOcclusionValue; }
    @Override public QuadEmitter ambientOcclusion(TriState value) { ambientOcclusionValue = value; return this; }
    @Override public FoilType foilType() { return foilTypeValue; }
    @Override public QuadEmitter foilType(FoilType value) { foilTypeValue = value; return this; }
    @Override public ShadeMode shadeMode() { return shadeModeValue; }
    @Override public QuadEmitter shadeMode(ShadeMode value) { shadeModeValue = value; return this; }
    @Override public boolean animated() { return animatedValue; }
    @Override public QuadEmitter animated(boolean value) { animatedValue = value; return this; }
    @Override public int tintIndex() { return tintIndexValue; }
    @Override public QuadEmitter tintIndex(int value) { tintIndexValue = value; return this; }
    @Override public int tag() { return tagValue; }
    @Override public QuadEmitter tag(int value) { tagValue = value; return this; }

    @Override
    public QuadEmitter postMaterialBake(Material.Baked material) {
        QuadEmitter.super.postMaterialBake(material);
        bakedSprite = material.sprite();
        return this;
    }

    @Override
    public QuadEmitter fromBakedQuad(BakedQuad quad) {
        clear();
        var material = quad.materialInfo();
        for (int vertex = 0; vertex < 4; vertex++) {
            pos(vertex, quad.position(vertex));
            long uv = quad.packedUV(vertex);
            uv(vertex, UVPair.unpackU(uv), UVPair.unpackV(uv));
            lightmap(vertex, material.lightEmission() << 4);
        }
        nominalFace(quad.direction());
        atlas(QuadAtlas.ofLocation(material.sprite().atlasLocation()));
        chunkLayer(material.layer());
        itemRenderType(material.itemRenderType());
        tintIndex(material.tintIndex());
        diffuseShade(material.shade());
        animated(material.sprite().isAnimated());
        bakedSprite = material.sprite();
        return this;
    }

    @Override
    public QuadEmitter copyFrom(QuadView source) {
        if (source == this) return this;
        clear();
        for (int vertex = 0; vertex < 4; vertex++) {
            pos(vertex, source.x(vertex), source.y(vertex), source.z(vertex));
            color(vertex, source.color(vertex));
            uv(vertex, source.u(vertex), source.v(vertex));
            lightmap(vertex, source.lightmap(vertex));
            if (source.hasNormal(vertex)) {
                normal(vertex, source.normalX(vertex), source.normalY(vertex), source.normalZ(vertex));
            }
        }
        cullFace(source.cullFace());
        nominalFace(source.nominalFace());
        atlas(source.atlas());
        chunkLayer(source.chunkLayer());
        itemRenderType(source.itemRenderType());
        emissive(source.emissive());
        diffuseShade(source.diffuseShade());
        ambientOcclusion(source.ambientOcclusion());
        foilType(source.foilType());
        shadeMode(source.shadeMode());
        animated(source.animated());
        tintIndex(source.tintIndex());
        tag(source.tag());
        bakedSprite = source instanceof RemixRenderContext captured ? captured.bakedSprite : null;
        return this;
    }

    @Override public float x(int vertex) { return positions[vertex * 3]; }
    @Override public float y(int vertex) { return positions[vertex * 3 + 1]; }
    @Override public float z(int vertex) { return positions[vertex * 3 + 2]; }
    @Override public float posByIndex(int vertex, int coordinate) { return positions[vertex * 3 + coordinate]; }
    @Override public int color(int vertex) { return colors[vertex]; }
    @Override public float u(int vertex) { return uvs[vertex * 2]; }
    @Override public float v(int vertex) { return uvs[vertex * 2 + 1]; }
    @Override public int lightmap(int vertex) { return lightmaps[vertex]; }
    @Override public boolean hasNormal(int vertex) { return normalSet[vertex]; }
    @Override public float normalX(int vertex) { return normalSet[vertex] ? normals[vertex * 3] : Float.NaN; }
    @Override public float normalY(int vertex) { return normalSet[vertex] ? normals[vertex * 3 + 1] : Float.NaN; }
    @Override public float normalZ(int vertex) { return normalSet[vertex] ? normals[vertex * 3 + 2] : Float.NaN; }

    @Override
    public Vector3f copyPos(int vertex, Vector3f target) {
        return (target == null ? new Vector3f() : target).set(x(vertex), y(vertex), z(vertex));
    }

    @Override
    public Vector2f copyUv(int vertex, Vector2f target) {
        return (target == null ? new Vector2f() : target).set(u(vertex), v(vertex));
    }

    @Override
    public Vector3f copyNormal(int vertex, Vector3f target) {
        if (!normalSet[vertex]) return null;
        return (target == null ? new Vector3f() : target).set(normalX(vertex), normalY(vertex), normalZ(vertex));
    }

    @Override
    public Vector3fc faceNormal() {
        if (normalDirty) {
            float ax = x(2) - x(0), ay = y(2) - y(0), az = z(2) - z(0);
            float bx = x(3) - x(1), by = y(3) - y(1), bz = z(3) - z(1);
            faceNormal.set(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
            if (faceNormal.lengthSquared() > 0) faceNormal.normalize();
            normalDirty = false;
        }
        return faceNormal;
    }

    @Override
    public Direction lightFace() {
        Vector3fc normal = faceNormal();
        return Direction.getApproximateNearest(normal.x(), normal.y(), normal.z());
    }

    @Override
    public void buffer(int overlay, VertexConsumer target) {
        buffer(overlay, null, target);
    }

    @Override
    public void buffer(int overlay, PoseStack.Pose pose, VertexConsumer target) {
        Vector3fc face = faceNormal();
        for (int vertex = 0; vertex < 4; vertex++) {
            bufferPosition.set(x(vertex), y(vertex), z(vertex));
            if (hasNormal(vertex)) bufferNormal.set(normalX(vertex), normalY(vertex), normalZ(vertex));
            else bufferNormal.set(face);
            if (pose != null) {
                pose.pose().transformPosition(bufferPosition);
                pose.transformNormal(bufferNormal, bufferNormal);
            }
            target.addVertex(bufferPosition.x, bufferPosition.y, bufferPosition.z, colors[vertex],
                    u(vertex), v(vertex), overlay, lightmaps[vertex],
                    bufferNormal.x, bufferNormal.y, bufferNormal.z);
        }
    }
}
