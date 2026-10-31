/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.nvidia.rtxremix.TextureFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExternalImageFormatProperties;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatProperties2;
import org.lwjgl.vulkan.VkImageMemoryRequirementsInfo2;
import org.lwjgl.vulkan.VkImportMemoryWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedRequirements;
import org.lwjgl.vulkan.VkMemoryRequirements2;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalImageFormatInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK11.*;

/** An imported allocation. The Win32 handle belongs to Remix, not to this object. */
final class VulkanInteropImage implements Destroyable {
    private final VkDevice device;
    final long sharedHandle;
    final long allocationSize;
    final int width;
    final int height;
    final int remixFormat;
    private long image;
    private long memory;

    VulkanInteropImage(VkDevice device, long sharedHandle, long allocationSize,
                       int width, int height, int remixFormat, boolean writable) {
        if (sharedHandle == 0 || sharedHandle == -1L || allocationSize <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid Remix shared image");
        }
        this.device = device;
        this.sharedHandle = sharedHandle;
        this.allocationSize = allocationSize;
        this.width = width;
        this.height = height;
        this.remixFormat = remixFormat;
        int format = vkFormat(remixFormat);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        if (writable) usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var externalFormat = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack)
                    .sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var formatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                    .pNext(externalFormat).format(format).type(VK_IMAGE_TYPE_2D)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).flags(VK_IMAGE_CREATE_ALIAS_BIT);
            var externalProperties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
            var properties = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(externalProperties);
            VulkanInterop.check(vkGetPhysicalDeviceImageFormatProperties2(
                    device.getPhysicalDevice(), formatInfo, properties), "query shared image format");
            int externalFeatures = externalProperties.externalMemoryProperties().externalMemoryFeatures();
            if ((externalFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0) {
                throw new IllegalStateException("Minecraft's Vulkan device cannot import the Remix image format " + format);
            }

            var externalImage = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var imageInfo = VkImageCreateInfo.calloc(stack).sType$Default().pNext(externalImage)
                    .imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .flags(VK_IMAGE_CREATE_ALIAS_BIT).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            var handle = stack.mallocLong(1);
            VulkanInterop.check(vkCreateImage(device, imageInfo, null, handle), "create shared image");
            image = handle.get(0);

            var dedicated = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
            var requirements = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicated);
            vkGetImageMemoryRequirements2(device,
                    VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image), requirements);
            if (requirements.memoryRequirements().size() > allocationSize) {
                throw new IllegalStateException("Remix allocation is smaller than Minecraft's shared image; "
                        + "both renderers must use the same GPU and image layout");
            }
            var importInfo = VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT).handle(sharedHandle);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(importInfo)
                    .allocationSize(allocationSize).memoryTypeIndex(deviceMemoryType(
                            requirements.memoryRequirements().memoryTypeBits(), stack));
            if (dedicated.requiresDedicatedAllocation()
                    || (externalFeatures & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) != 0) {
                allocation.pNext(VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image));
            }
            VulkanInterop.check(vkAllocateMemory(device, allocation, null, handle), "import Remix image memory");
            memory = handle.get(0);
            VulkanInterop.check(vkBindImageMemory(device, image, memory, 0), "bind Remix image memory");
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    /** Opaque handles cannot be passed to vkGetMemoryWin32HandlePropertiesKHR. */
    private int deviceMemoryType(int allowedTypes, MemoryStack stack) {
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), properties);
        // The runtime exports a device-local image allocation. Prefer the GPU-only
        // heap over a host-visible BAR mapping when both satisfy the image.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < properties.memoryTypeCount(); index++) {
                int flags = properties.memoryTypes(index).propertyFlags();
                if ((allowedTypes & (1 << index)) != 0 && (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0
                        && (pass != 0 || (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0)) {
                    return index;
                }
            }
        }
        throw new IllegalStateException("No device-local memory type for the Remix shared image");
    }

    boolean matches(long handle, long bytes, int width, int height, int format) {
        return sharedHandle == handle && allocationSize == bytes && this.width == width
                && this.height == height && remixFormat == format;
    }

    long image() { return image; }

    static int vkFormat(int format) {
        return switch (format) {
            case TextureFormat.RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case TextureFormat.RGBA8_SRGB -> VK_FORMAT_R8G8B8A8_SRGB;
            case TextureFormat.BGRA8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM;
            case TextureFormat.BGRA8_SRGB -> VK_FORMAT_B8G8R8A8_SRGB;
            case TextureFormat.RGBA16_SFLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case TextureFormat.RGBA32_SFLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case TextureFormat.R32_SFLOAT -> VK_FORMAT_R32_SFLOAT;
            default -> throw new IllegalArgumentException("Unsupported Remix shared image format: " + format);
        };
    }

    @Override
    public void destroy() {
        if (image != 0) vkDestroyImage(device, image, null);
        if (memory != 0) vkFreeMemory(device, memory, null);
        image = 0;
        memory = 0;
    }
}
