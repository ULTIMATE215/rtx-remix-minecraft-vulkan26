/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.mixin.RebuildTaskMixin;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sun.misc.Unsafe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Exercises the task wrapper with the extraction frame's independent region reference. */
class RebuildTaskLifecycleTest {
    @Test
    void taskNullPointerIsReportedWithItsCauseAndCannotBeSilentlyRequeued() throws Exception {
        Fixture fixture = new Fixture();
        NullPointerException originalFailure = new NullPointerException("block capture failed");

        InvocationTargetException call = assertThrows(InvocationTargetException.class,
                () -> fixture.run(arguments -> { throw originalFailure; }));
        IllegalStateException reported = assertInstanceOf(IllegalStateException.class, call.getCause());
        assertSame(originalFailure, reported.getCause());
        assertTrue(reported.getMessage().contains(SectionPos.of(fixture.region.node).toString()));
        assertEquals(1, fixture.region.references, "only the extraction frame still owns the snapshot");
        assertSame(fixture.region.token, SectionBuildCapture.tokenOr(fixture.region.token),
                "the failed task must clear its thread-local identity");

        assertEquals(SectionTaskResult.CANCELLED, fixture.run(arguments -> fail("cannot read a released region")));
        fixture.cancel();
        assertEquals(1, fixture.region.references, "later cancellation must not release the task twice");
        fixture.region.rtxremix$release();
        assertEquals(0, fixture.region.references);
    }

    @Test
    void successfulTaskUsesItsCancellationIdentityAndReleasesOnlyItsOwnReference() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(2, fixture.region.references);
        assertEquals(SectionTaskResult.SUCCESSFUL, fixture.run(arguments -> {
            var current = SectionBuildCapture.tokenOr(fixture.region.token);
            assertNotSame(fixture.region.token, current);
            assertEquals(fixture.region.token.queueGeneration(), current.queueGeneration());
            assertEquals(fixture.region.token.chunkToken(), current.chunkToken());
            assertFalse(current.isCancelled());
            return SectionTaskResult.SUCCESSFUL;
        }));
        assertEquals(1, fixture.region.references);
        fixture.cancel();
        assertEquals(1, fixture.region.references);
        assertSame(fixture.region.token, SectionBuildCapture.tokenOr(fixture.region.token));
    }

    @Test
    void cancellationBeforeStartPreventsReadingTheReleasedSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        fixture.cancel();
        fixture.cancel();
        assertEquals(1, fixture.region.references);
        assertEquals(SectionTaskResult.CANCELLED, fixture.run(arguments -> fail("cancelled task ran")));
        assertEquals(1, fixture.region.references);
    }

    private static final class Fixture {
        final RebuildTaskMixin task = new RebuildTaskMixin() {};
        final Region region;

        Fixture() throws Exception {
            // The vanilla region constructor queries a live ClientLevel's light
            // engine. This fixture exercises reference ownership, not world reads.
            var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            region = (Region) unsafe.allocateInstance(Region.class);
            region.references = 1;
            region.node = SectionPos.asLong(-2, -4, 1);
            region.token = BlockDataQueue.beginCapture(-2, 1);
            var field = RebuildTaskMixin.class.getDeclaredField("region");
            field.setAccessible(true);
            field.set(task, region);
            method("rtxremix$retainRegion", SectionRenderDispatcher.RenderSection.class,
                    RenderSectionRegion.class, boolean.class, CallbackInfo.class)
                    .invoke(task, null, region, false, new CallbackInfo("<init>", false));
        }

        Object run(Operation<SectionTaskResult> body) throws Exception {
            return method("rtxremix$consumeRegion", SectionBufferBuilderPack.class, Operation.class)
                    .invoke(task, null, body);
        }

        void cancel() throws Exception {
            method("rtxremix$cancelRegion", CallbackInfo.class)
                    .invoke(task, new CallbackInfo("cancel", false));
        }
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method method = RebuildTaskMixin.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static final class Region extends RenderSectionRegion implements RegionCaptureAccess {
        int references;
        long node;
        BlockDataQueue.CaptureToken token;

        private Region() { super(null, 0, 0, 0, null); }
        @Override public long rtxremix$sectionNode() { return node; }
        @Override public int rtxremix$snapshotMask() { return 0; }
        @Override public BlockDataQueue.CaptureToken rtxremix$captureToken() { return token; }
        @Override public void rtxremix$retain() { references++; }
        @Override public void rtxremix$release() {
            assertTrue(references > 0, "snapshot released more than once");
            references--;
        }
    }
}
