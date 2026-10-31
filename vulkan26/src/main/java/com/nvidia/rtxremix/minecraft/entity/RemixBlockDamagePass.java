/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.InstanceCategoryBit;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captures vanilla's already-selected damage model parts as a decal over the terrain.
 * The feature submission uses an identity root plus the model offset, so vertices
 * remain block-local. Vanilla's sheeted decal projection supplies the crack UVs;
 * SheetedDecalTextureGeneratorMixin sends those UVs before the capture closes a vertex.
 * Position owner tokens keep instances stable until vanilla stops submitting a crack.
 */
public final class RemixBlockDamagePass implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixBlockDamagePass.class);

    private static final String TYPE_KEY = "minecraft:block_breaking";

    /**
     * The crack is coplanar with the terrain face it is drawn on, which is the case
     * terrain's own overlay category already solves with {@code DECAL_STATIC}: the
     * runtime offsets decal quads off the surface beneath and blends them over it. The
     * runtime handles {@code DECAL_DYNAMIC} identically and has deprecated its option,
     * so the static bit is used for both.
     */
    static final RemixEntitySubmitter.PartCategoryPolicy TERRAIN_DECAL =
            role -> InstanceCategoryBit.DECAL_STATIC;

    /**
     * Vanilla's damage render with the matrix stack and consumer left open, so the pass
     * can supply its own. The mixin's {@code Operation} handle stays in the mixin.
     */
    @FunctionalInterface
    public interface BlockDamageRenderCall {
        void render(PoseStack matrices, SubmitNodeCollector collector);
    }

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixFeatureCapture features = new RemixFeatureCapture();
    private final PositionOwners owners = new PositionOwners();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Block damage");
    private float[] reusableBoneData = new float[0];

    /**
     * Renders one broken block's damage model through vanilla's renderer on the identity
     * stack and submits the capture inside the submitter's open frame.
     *
     * @param stage the destroy stage vanilla selected for the block, 0 to 9
     */
    public void capture(BlockPos pos, int stage, BlockDamageRenderCall call,
                        RemixEntitySubmitter submitter) {
        EntityRenderContext ctx = EntityRenderContext.get();
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            call.render(matrices, features.begin(provider));
            features.flush();
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "block damage", pos, e);
            return;
        } finally {
            // Both restore even if the renderer threw: the model renderer translates the
            // root in place, and a capture left open would make the next one look nested.
            features.discard();
            matrices.reset();
            ctx.reset();
        }
        if (layers.isEmpty()) {
            return;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        RemixEntityPass.EntityCaptureResult result = new RemixEntityPass.EntityCaptureResult(
                owners.ownerFor(pos.asLong()),
                Vec3.atLowerCornerOf(pos),
                0.0f,
                layers,
                reusableBoneData,
                ctx.getBoneCount(),
                TYPE_KEY,
                false);
        try {
            submitter.submitEntity(result, TERRAIN_DECAL);
        } catch (Exception e) {
            failures.noteFailure("submitting", "block damage", pos, e);
        }
    }

    /**
     * Drops the owner tokens of positions not captured this frame. Called once per frame
     * from {@code renderAndSubmitFrame}; retiring their scene instances belongs to the
     * submitter's {@code endFrame}.
     */
    public void finishFrame() {
        owners.endFrame();
        failures.endFrame();
    }

    @Override
    public void close() {
        features.close();
        owners.owners.clear();
    }

    /**
     * One identity token per broken block position, kept while the position is rendered.
     * Tokens are handed out during a frame and the ones not handed out are dropped at
     * {@link #endFrame}, so a position rendered every frame keeps one token and a
     * position that stops being rendered starts over with a new one if it returns.
     */
    static final class PositionOwners {
        private static final class Owner {
            long lastSeenFrame;
        }

        private final Long2ObjectOpenHashMap<Owner> owners = new Long2ObjectOpenHashMap<>();
        private long frame;

        Object ownerFor(long packedPos) {
            Owner owner = owners.get(packedPos);
            if (owner == null) {
                owner = new Owner();
                owners.put(packedPos, owner);
            }
            owner.lastSeenFrame = frame;
            return owner;
        }

        void endFrame() {
            if (!owners.isEmpty()) {
                owners.values().removeIf(owner -> owner.lastSeenFrame != frame);
            }
            frame++;
        }

        int size() {
            return owners.size();
        }
    }

}
