/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.lwjgl.system.Platform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @Shadow @Final @Mutable
    public static Set<String> REQUIRED_DEVICE_EXTENSIONS;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void rtxremix$requireExternalImages(CallbackInfo callback) {
        if (Platform.get() != Platform.WINDOWS) return;
        Set<String> extensions = new HashSet<>(REQUIRED_DEVICE_EXTENSIONS);
        extensions.add(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
        extensions.add(VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
        REQUIRED_DEVICE_EXTENSIONS = Set.copyOf(extensions);
    }
}
