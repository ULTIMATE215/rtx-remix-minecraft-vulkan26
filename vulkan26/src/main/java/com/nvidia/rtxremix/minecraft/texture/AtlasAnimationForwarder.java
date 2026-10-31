/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects animated sprite upload patches each tick and flushes them to Remix
 * in a single batched call before present.
 *
 * <p>LabPBR-aware: sprites that have {@code _n} / {@code _s} siblings are
 * re-processed on every animation frame so AO (from {@code _n.b}) and the
 * animated LabPBR material channels in {@code _s} stay in phase with albedo.
 * Without this, later albedo frames would overwrite the AO-modulated atlas
 * while roughness, metallic, SSS, and emissive atlases stayed stuck on the
 * init-time sidecar frame. Non-LabPBR sprites are copied as albedo only.
 *
 * <p>All patches for one frame across every animated atlas texture go out in
 * one {@code remixapi_UpdateTextureFromMemory} call using the multi-handle
 * batch API. This amortizes the JNI crossing and, on the dxvk side, lets mip
 * regeneration be scoped to the dirty rect per atlas instead of regenerating
 * the whole atlas once per atlas.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasAnimationForwarder.class);

    // All state and NativeImage pointers belong to the render thread. The atlas
    // clears this forwarder before closing its sprites during resource reload.
    private static boolean active;
    private static long generation;
    private static Thread expectedThread;
    private static TextureAtlas atlas;
    private static Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBRMap;
    private static final List<PatchRecord> pendingPatches = new ArrayList<>();
    private static final Map<Identifier, PatchRecord> records = new HashMap<>();
    private static byte[] scratch = new byte[64 * 64 * 4];
    private static ByteBuffer interpolated = ByteBuffer.allocate(64 * 64 * 4);

    private static final class PatchRecord {
        final Identifier id;
        final BlockTextureManager.AnimatedSpriteLabPBR pbr;
        final int dstX, dstY, width, height, padding, rowPitch;
        final long imagePointer;
        int srcX, srcY, nextX, nextY;
        float nextBlend;
        boolean pending;

        PatchRecord(TextureAtlasSprite sprite) {
            id = sprite.contents().name();
            pbr = labPBRMap.get(id.toString());
            dstX = sprite.getX();
            dstY = sprite.getY();
            width = sprite.contents().width();
            height = sprite.contents().height();
            padding = sprite.padding;
            NativeImage image = ((SpriteContentsAccessor) sprite.contents()).getImage();
            imagePointer = image.getPointer();
            rowPitch = image.getWidth() * 4;
        }
    }

    public static void init(Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
                            TextureAtlas source) {
        discardPending();
        records.clear();
        labPBRMap = labPBR;
        atlas = source;
        expectedThread = Thread.currentThread();
        active = true;
        generation++;
        ((AtlasAnimationSource) source).rtxremix$captureAnimations();
        LOGGER.info("AtlasAnimationForwarder initialized ({} LabPBR sprites)", labPBR.size());
    }

    public static long generation() {
        return active ? generation : 0;
    }

    /** Coalesces multiple vanilla ticks into the final state presented in this render frame. */
    public static boolean addFrame(TextureAtlasSprite sprite, int frameColumns,
                                    int currentFrame, int nextFrame, float nextBlend) {
        if (!active || !BlockTextureManager.BLOCK_ATLAS_ID.equals(sprite.atlasLocation())) return false;
        if (Thread.currentThread() != expectedThread) {
            LOGGER.error("Animation capture must run on the render thread; dropping frame");
            return false;
        }
        // A stitched replacement cannot reuse an old sprite's native memory or destination.
        if (((SpriteAtlasAccessor) atlas).getSprites().get(sprite.contents().name()) != sprite) return false;
        PatchRecord record = records.get(sprite.contents().name());
        if (record == null) {
            record = new PatchRecord(sprite);
            records.put(record.id, record);
        }
        record.srcX = currentFrame % frameColumns * record.width;
        record.srcY = currentFrame / frameColumns * record.height;
        record.nextX = nextFrame % frameColumns * record.width;
        record.nextY = nextFrame / frameColumns * record.height;
        record.nextBlend = nextBlend;
        if (!record.pending) {
            record.pending = true;
            pendingPatches.add(record);
        }
        return true;
    }

    private static ByteBuffer source(PatchRecord p) {
        // A frame need not include the trailing pixels in the final source row.
        int span = (p.height - 1) * p.rowPitch + p.width * 4;
        long currentPointer = p.imagePointer + (long) p.srcY * p.rowPitch + p.srcX * 4L;
        ByteBuffer current = MemoryUtil.memByteBuffer(currentPointer, span);
        if (p.nextBlend == 0 || (p.srcX == p.nextX && p.srcY == p.nextY)) return current;
        int bytes = p.width * p.height * 4;
        if (interpolated.capacity() < bytes) interpolated = ByteBuffer.allocate(bytes);
        long nextPointer = p.imagePointer + (long) p.nextY * p.rowPitch + p.nextX * 4L;
        ByteBuffer next = MemoryUtil.memByteBuffer(nextPointer, span);
        // Both vanilla frame textures are RGBA8_UNORM. Its shader blends alpha
        // too, with the same quantized progress supplied by AnimationState.
        for (int y = 0; y < p.height; y++) {
            for (int x = 0; x < p.width * 4; x++) {
                int offset = y * p.rowPitch + x;
                int a = current.get(offset) & 255;
                int b = next.get(offset) & 255;
                interpolated.put(y * p.width * 4 + x,
                        (byte) Math.round(a * (1.0f - p.nextBlend) + b * p.nextBlend));
            }
        }
        return interpolated;
    }

    /**
     * Builds one multi-handle batch covering animated albedo and LabPBR atlas
     * sidecars, then issues a single {@code remixapi_UpdateTextureFromMemory}
     * call. LabPBR data is applied per patch using pre-computed tables cached
     * at init.
     *
     * @param api            the Remix API handle
     * @param albedoHandle   handle of the albedo atlas texture (required)
     * @param emissiveHandle handle of the emissive atlas, or 0 if none exists
     * @param roughnessHandle handle of the roughness atlas, or 0 if none exists
     * @param metallicHandle handle of the metallic atlas, or 0 if none exists
     * @param sssHandle      handle of the SSS atlas, or 0 if none exists
     */
    public static void flush(RemixAPI api, long albedoHandle, long emissiveHandle,
                             long roughnessHandle, long metallicHandle, long sssHandle) {
        int count = pendingPatches.size();
        if (count == 0 || albedoHandle == 0) {
            return;
        }

        List<ByteBuffer> ownedBuffers = new ArrayList<>(count * 2);

        try {
            TexturePatch[] albedoPatches = new TexturePatch[count];
            List<TexturePatch> roughnessPatches = new ArrayList<>();
            List<TexturePatch> metallicPatches = new ArrayList<>();
            List<TexturePatch> emissivePatches = new ArrayList<>();
            List<TexturePatch> sssPatches = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                PatchRecord p = pendingPatches.get(i);
                BlockTextureManager.AnimatedSpriteLabPBR pbr = p.pbr;

                final int patchBytes = p.width * p.height * 4;
                final int patchRowBytes = p.width * 4;
                ensureScratch(patchBytes);
                ByteBuffer srcBuf = source(p);
                int srcRowPitch = srcBuf == interpolated ? patchRowBytes : p.rowPitch;

                // --- Albedo patch ---
                if (pbr != null && pbr.aoTable != null) {
                    LabPBRConverter.applyAOToPatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        pbr.aoTable, pbr.aoW, pbr.aoH,
                        p.srcX, p.srcY, p.nextX, p.nextY, p.nextBlend,
                        p.width, p.height);
                } else {
                    LabPBRConverter.copyAlbedoPatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        p.width, p.height);
                }
                ByteBuffer albedoDst = paddedBuffer(p, 4);
                ownedBuffers.add(albedoDst);

                albedoPatches[i] = createPatch(p, albedoDst, patchRowBytes, TextureFormat.RGBA8_UNORM);

                // --- _s RGB material channels ---
                // LabPBR packs roughness/metallic/SSS into _s.rgb. These
                // atlases are separate Remix textures, so they need matching
                // animation patches just like _s.alpha emissive.
                if (pbr != null && pbr.sRoughnessTable != null && roughnessHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sRoughnessTable, pbr.sW, pbr.sH,
                        p.srcX, p.srcY,
                        p.nextX, p.nextY,
                        p.nextBlend,
                        p.width, p.height);
                    ByteBuffer roughnessDst = paddedBuffer(p, 1);
                    ownedBuffers.add(roughnessDst);
                    roughnessPatches.add(createPatch(p, roughnessDst, p.width, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.sMetallicTable != null && metallicHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sMetallicTable, pbr.sW, pbr.sH,
                        p.srcX, p.srcY,
                        p.nextX, p.nextY,
                        p.nextBlend,
                        p.width, p.height);
                    ByteBuffer metallicDst = paddedBuffer(p, 1);
                    ownedBuffers.add(metallicDst);
                    metallicPatches.add(createPatch(p, metallicDst, p.width, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.hasSss && pbr.sSssTable != null && sssHandle != 0) {
                    LabPBRConverter.buildSssPatch(
                        scratch, patchRowBytes,
                        pbr.sSssTable, pbr.sW, pbr.sH,
                        p.srcX, p.srcY,
                        p.nextX, p.nextY,
                        p.nextBlend,
                        p.width, p.height);
                    ByteBuffer sssDst = paddedBuffer(p, 4);
                    ownedBuffers.add(sssDst);
                    sssPatches.add(createPatch(p, sssDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
                }

                // --- Emissive patch (authored _s alpha, else a built-in mask) ---
                if (emissiveHandle == 0) continue;
                final boolean hasLabPBREmissive = pbr != null && pbr.hasPerPixelEmissive;
                BuiltinEmission.Mask builtinMask = hasLabPBREmissive ? null : BuiltinEmission.mask(p.id);

                // Skip sprites with neither. Vanilla block luminance affects
                // cast light, not surface emission.
                if (!hasLabPBREmissive && builtinMask == null) continue;

                if (hasLabPBREmissive) {
                    LabPBRConverter.buildEmissivePatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        pbr.sAlphaTable, pbr.sW, pbr.sH,
                        p.srcX, p.srcY,
                        p.nextX, p.nextY,
                        p.nextBlend,
                        p.width, p.height,
                        0f);
                } else {
                    // The mask is over the sprite's own UV space, which every animation frame shares.
                    LabPBRConverter.buildEmissiveMaskPatch(
                        scratch, patchRowBytes,
                        srcBuf, srcRowPitch,
                        p.width, p.height, builtinMask);
                }

                ByteBuffer emissiveDst = paddedBuffer(p, 4);
                ownedBuffers.add(emissiveDst);

                emissivePatches.add(createPatch(p, emissiveDst, patchRowBytes, TextureFormat.RGBA8_UNORM));
            }

            // One batch per frame: the runtime handles each entry's texture
            // independently, so all five channels travel in a single call.
            List<TextureBatchEntry> entries = new ArrayList<>(5);
            addBatchEntry(entries, albedoHandle, albedoPatches);
            addBatchEntry(entries, roughnessHandle, roughnessPatches);
            addBatchEntry(entries, metallicHandle, metallicPatches);
            addBatchEntry(entries, emissiveHandle, emissivePatches);
            addBatchEntry(entries, sssHandle, sssPatches);

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = entries.toArray(new TextureBatchEntry[0]);
            api.updateTextureFromMemory(info);

        } catch (Exception e) {
            LOGGER.error("Error flushing animation patches", e);
        } finally {
            for (ByteBuffer buffer : ownedBuffers) {
                DirectBufferPool.release(buffer);
            }
            discardPending();
        }
    }

    private static ByteBuffer paddedBuffer(PatchRecord p, int channels) {
        int bytes = (p.width + 2 * p.padding) * (p.height + 2 * p.padding) * channels;
        ByteBuffer buffer = DirectBufferPool.acquire(bytes);
        SpriteLayout.writePadded(buffer, scratch, p.width, p.height, channels, p.padding);
        return buffer.flip();
    }

    private static void ensureScratch(int byteCount) {
        if (scratch.length < byteCount) {
            scratch = new byte[byteCount];
        }
    }

    private static TexturePatch createPatch(PatchRecord p, ByteBuffer data, int rowPitch, int format) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = p.dstX;
        patch.dstY = p.dstY;
        patch.width = p.width + 2 * p.padding;
        patch.height = p.height + 2 * p.padding;
        patch.pixelData = data;
        patch.rowPitch = patch.width * (rowPitch / p.width);
        patch.format = format;
        return patch;
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, TexturePatch[] patches) {
        if (handle == 0 || patches.length == 0) {
            return;
        }
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = handle;
        entry.patches = patches;
        entries.add(entry);
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, List<TexturePatch> patches) {
        if (handle == 0 || patches.isEmpty()) {
            return;
        }
        addBatchEntry(entries, handle, patches.toArray(new TexturePatch[0]));
    }

    private static void discardPending() {
        for (int i = 0; i < pendingPatches.size(); i++) pendingPatches.get(i).pending = false;
        pendingPatches.clear();
    }

    public static void clear() {
        active = false;
        discardPending();
        records.clear();
        labPBRMap = null;
        atlas = null;
        expectedThread = null;
    }

    public static void onAtlasClosed(TextureAtlas source) {
        if (atlas == source) clear();
    }

    public static void pause() {
        active = false;
        discardPending();
    }

    public static void resume() {
        if (atlas == null) return;
        active = true;
        generation++;
        ((AtlasAnimationSource) atlas).rtxremix$captureAnimations();
    }

    private AtlasAnimationForwarder() {}
}
