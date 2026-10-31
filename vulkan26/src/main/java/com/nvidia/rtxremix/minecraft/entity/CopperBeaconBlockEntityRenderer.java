/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Renders the independently rotating yoke and lamp head above the static mount. */
public final class CopperBeaconBlockEntityRenderer
        implements BlockEntityRenderer<CopperBeaconBlockEntity, CopperBeaconBlockEntityRenderer.RenderState> {

    public static final ModelLayerLocation BODY_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "body");
    public static final ModelLayerLocation LENS_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "lens");
    private static final SpriteId COPPER_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            Identifier.withDefaultNamespace("block/copper_block"));
    static final Identifier LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens");
    static final Identifier UNLIT_LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens_unlit");
    private static final SpriteId LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            LENS_SPRITE_ID);
    private static final SpriteId UNLIT_LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            UNLIT_LENS_SPRITE_ID);

    private final ModelPart yoke;
    private final ModelPart head;
    private final ModelPart lens;
    private final Model<RenderState> bodyModel;
    private final Model<RenderState> lensModel;
    private final SpriteGetter sprites;

    public CopperBeaconBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart bodyRoot = context.bakeLayer(BODY_MODEL_LAYER);
        yoke = bodyRoot.getChild("yoke");
        head = yoke.getChild("head");
        lens = context.bakeLayer(LENS_MODEL_LAYER).getChild("lens");
        sprites = context.sprites();
        // These are this renderer's own baked cubes. Remap once, before they can
        // enter the retained mesh cache, so deferred vanilla and Remix submissions
        // use identical UVs without a per-frame vertex-consumer wrapper.
        yoke.visit(new PoseStack(), (entry, path, index, cube) -> {
            for (ModelPart.Polygon polygon : cube.polygons) {
                ModelPart.Vertex[] vertices = polygon.vertices();
                for (int i = 0; i < vertices.length; i++) {
                    ModelPart.Vertex vertex = vertices[i];
                    vertices[i] = vertex.remap(vertex.u(), copperTextureV(vertex.v()));
                }
            }
        });
        bodyModel = new BeaconModel(yoke);
        lensModel = new BeaconModel(lens);
    }

    public static LayerDefinition getBodyTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        PartDefinition yokeData = root.addOrReplaceChild(
                "yoke",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(0.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-5.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f)
                        .texOffs(0, 0).addBox(3.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        yokeData.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-6.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(4.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f),
                PartPose.ZERO);
        return LayerDefinition.create(modelData, 16, 16);
    }

    public static LayerDefinition getLensTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        root.addOrReplaceChild(
                "lens",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -3.0f, -4.01f, 6.0f, 6.0f, 0.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        return LayerDefinition.create(modelData, 6, 6);
    }

    @Override
    public RenderState createRenderState() {
        return new RenderState();
    }

    @Override
    public void extractRenderState(CopperBeaconBlockEntity beacon, RenderState renderState,
                                   float tickDelta, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(beacon, renderState, tickDelta, cameraPos, breakProgress);
        BlockState state = beacon.getBlockState();
        Direction up = state.getValue(CopperBeaconBlock.MOUNT);
        Direction forward = state.getValue(BlockStateProperties.FACING);
        double animationTick = CopperBeaconAnimationClock.sample(
                beacon.getLevel(), tickDelta);

        CopperBeaconPose.evaluate(
                up,
                forward,
                beacon.yawAngleAt(animationTick),
                beacon.pitchAngleAt(animationTick),
                renderState.pose);
        renderState.lensTexture = lensTexture(beacon.isActive(), beacon.getIntensityMultiplier());
        renderState.lensTint = beacon.getLensTint();
    }

    @Override
    public void submit(RenderState state, PoseStack matrices, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        matrices.pushPose();
        try {
            matrices.translate(0.5, 0.5, 0.5);
            matrices.mulPose(state.pose.mountingRotation());
            matrices.translate(-0.5, -0.5, -0.5);
            collector.submitModel(bodyModel, state, matrices,
                    COPPER_TEXTURE.renderType(RenderTypes::entitySolid), state.lightCoords,
                    OverlayTexture.NO_OVERLAY, -1, sprites.get(COPPER_TEXTURE), 0, state.breakProgress);
            collector.submitModel(lensModel, state, matrices,
                    state.lensTexture.renderType(RenderTypes::entitySolid), state.lightCoords,
                    OverlayTexture.NO_OVERLAY, state.lensTint, sprites.get(state.lensTexture), 0,
                    state.breakProgress);
        } finally {
            matrices.popPose();
        }
    }

    static Quaternionf mountingRotation(Direction upDirection, Direction forwardDirection) {
        return CopperBeaconPose.mountingRotation(
                upDirection, forwardDirection, new Quaternionf());
    }

    static void applyArticulation(
            ModelPart yoke,
            ModelPart head,
            ModelPart lens,
            CopperBeaconPose.Pose pose) {
        yoke.yRot = pose.modelYawRadians();
        yoke.xRot = 0.0f;
        head.yRot = 0.0f;
        head.xRot = pose.modelPitchRadians();
        lens.yRot = pose.modelYawRadians();
        lens.xRot = pose.modelPitchRadians();
    }

    static SpriteId lensTexture(boolean active, float intensityMultiplier) {
        return active && intensityMultiplier > 0.0f
                ? LENS_TEXTURE
                : UNLIT_LENS_TEXTURE;
    }

    static float copperTextureV(float v) {
        return 0.25f + v * 0.5f;
    }

    public static final class RenderState extends BlockEntityRenderState {
        final CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
        SpriteId lensTexture = UNLIT_LENS_TEXTURE;
        int lensTint;
    }

    /** Model angles are applied when vanilla prepares this submission, not when it queues it. */
    private final class BeaconModel extends Model<RenderState> {
        BeaconModel(ModelPart root) {
            super(root, RenderTypes::entitySolid);
        }

        @Override
        public void setupAnim(RenderState state) {
            super.setupAnim(state);
            applyArticulation(yoke, head, lens, state.pose);
        }
    }
}
