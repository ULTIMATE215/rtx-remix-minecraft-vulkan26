/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkImportSemaphoreWin32HandleInfoKHR;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR;
import static org.lwjgl.vulkan.VK11.VK_ERROR_INVALID_EXTERNAL_HANDLE;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
import static org.lwjgl.vulkan.VK11.VK_SUCCESS;

class VulkanWin32SemaphoreImportTest {
    private static final long DEVICE = 0x1234_0000_0001L;
    private static final long SEMAPHORE = 0x1234_0000_0002L;
    private static final long SHARED_HANDLE = 0x1234_0000_1808L;

    @Test
    void importsOpaqueWin32TimelineByHandleWithNullName() throws Exception {
        try (NativeImport function = new NativeImport(VK_SUCCESS)) {
            assertEquals(VK_SUCCESS, VulkanWin32SemaphoreImport.importTimeline(
                    DEVICE, SEMAPHORE, SHARED_HANDLE, function.address));

            assertEquals(1, function.calls);
            assertEquals(DEVICE, function.device);
            assertEquals(SEMAPHORE, function.semaphore);
            assertEquals(SHARED_HANDLE, function.sharedHandle);
            assertEquals(VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR, function.sType);
            assertEquals(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT, function.handleType);
            assertEquals(0, function.name, "import by handle requires a null name");
            assertEquals(0, function.flags, "timeline semaphore imports must be permanent");
            assertEquals(0, function.pNext);
        }
    }

    @Test
    void returnsNativeFailureForCallerCleanup() throws Exception {
        try (NativeImport function = new NativeImport(VK_ERROR_INVALID_EXTERNAL_HANDLE)) {
            assertEquals(VK_ERROR_INVALID_EXTERNAL_HANDLE, VulkanWin32SemaphoreImport.importTimeline(
                    DEVICE, SEMAPHORE, SHARED_HANDLE, function.address));
            assertEquals(1, function.calls);
        }
    }

    @Test
    void rejectsInvalidHandlesBeforeNativeDispatch() throws Exception {
        try (NativeImport function = new NativeImport(VK_SUCCESS)) {
            assertThrows(IllegalArgumentException.class, () -> VulkanWin32SemaphoreImport.importTimeline(
                    0, SEMAPHORE, SHARED_HANDLE, function.address));
            assertThrows(IllegalArgumentException.class, () -> VulkanWin32SemaphoreImport.importTimeline(
                    DEVICE, 0, SHARED_HANDLE, function.address));
            for (long invalidHandle : new long[]{0, -1L}) {
                assertThrows(IllegalArgumentException.class, () -> VulkanWin32SemaphoreImport.importTimeline(
                        DEVICE, SEMAPHORE, invalidHandle, function.address));
            }
            assertEquals(0, function.calls);
        }
    }

    @Test
    void rejectsMissingImportFunction() {
        assertThrows(IllegalStateException.class, () -> VulkanWin32SemaphoreImport.importTimeline(
                DEVICE, SEMAPHORE, SHARED_HANDLE, 0));
    }

    /** A native function pointer lets the real LWJGL JNI dispatch run without a Vulkan device. */
    private static final class NativeImport implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final int result;
        private final long address;
        private int calls;
        private long device;
        private long semaphore;
        private long sharedHandle;
        private long name;
        private long pNext;
        private int sType;
        private int handleType;
        private int flags;

        NativeImport(int result) throws ReflectiveOperationException {
            this.result = result;
            var method = MethodHandles.lookup().findVirtual(NativeImport.class, "invoke",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)).bindTo(this);
            address = Linker.nativeLinker().upcallStub(method,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    arena).address();
        }

        private int invoke(MemorySegment device, MemorySegment infoAddress) {
            calls++;
            this.device = device.address();
            var info = VkImportSemaphoreWin32HandleInfoKHR.create(infoAddress.address());
            semaphore = info.semaphore();
            sharedHandle = info.handle();
            name = MemoryUtil.memGetAddress(info.address() + VkImportSemaphoreWin32HandleInfoKHR.NAME);
            pNext = info.pNext();
            sType = info.sType();
            handleType = info.handleType();
            flags = info.flags();
            return result;
        }

        @Override
        public void close() {
            arena.close();
        }
    }
}
