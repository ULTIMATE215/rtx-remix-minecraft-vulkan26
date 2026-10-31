/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/** Sky compute assets; destruction is queued on Minecraft's encoder after their last submission. */
final class AtmosphericSkyResources implements Destroyable {
    private final VulkanDevice owner;
    private final VkDevice device;
    private final Image[] images = new Image[4];
    final Readback[] readbacks = new Readback[3];
    private long descriptorLayout, pipelineLayout, pipeline, descriptorPool, descriptorSet, sampler;

    AtmosphericSkyResources(VulkanDevice owner, String shader, int width, int height, int readbackBytes) {
        this.owner = owner;
        device = owner.vkDevice();
        try {
            images[0] = new Image(AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT,
                    VK_FORMAT_R32G32B32A32_SFLOAT, true);
            images[1] = new Image(width, height / 2, VK_FORMAT_R32G32B32A32_SFLOAT, false);
            images[2] = new Image(width, height / 2, VK_FORMAT_R32G32B32A32_SFLOAT, false);
            images[3] = new Image(width, height + 1, VK_FORMAT_R16G16B16A16_SFLOAT, false);
            for (int i = 0; i < readbacks.length; i++) readbacks[i] = new Readback(readbackBytes);
            createPipeline(shader);
            createDescriptors();
        } catch (Throwable failure) {
            destroy();
            throw failure;
        }
    }

    private void createPipeline(String source) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        long result = 0, module = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (compiler == 0 || options == 0) throw new IllegalStateException("Cannot initialize sky shader compiler");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, "sky_atmosphere.comp", "main", options);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("Cannot compile atmospheric sky: "
                        + (result == 0 ? "no compiler result" : shaderc_result_get_error_message(result)));
            }
            ByteBuffer binary = shaderc_result_get_bytes(result);
            if (binary == null) throw new IllegalStateException("Empty atmospheric sky shader");
            var handle = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(5, stack);
            for (int i = 0; i < 5; i++) {
                bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .descriptorType(i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, handle), "create sky descriptor layout");
            descriptorLayout = handle.get(0);
            // Five vec4s retain the parent's 80-byte parameter layout. Pass is the final int.
            var constants = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(84);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(constants), null, handle),
                    "create sky pipeline layout");
            pipelineLayout = handle.get(0);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(binary),
                    null, handle), "create sky shader module");
            module = handle.get(0);
            var create = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
            create.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, 0, create, null, handle), "create sky compute pipeline");
            pipeline = handle.get(0);
        } finally {
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            if (compiler != 0) shaderc_compiler_release(compiler);
        }
    }

    private void createDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            check(vkCreateSampler(device, samplerInfo, null, handle), "create sky LUT sampler");
            sampler = handle.get(0);
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(sizes), null, handle), "create sky descriptor pool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "allocate sky descriptors");
            descriptorSet = handle.get(0);
            var writes = VkWriteDescriptorSet.calloc(5, stack);
            for (int i = 0; i < 5; i++) {
                var image = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .imageView(images[i < 4 ? i : 0].view).sampler(i == 4 ? sampler : 0);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(image);
            }
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    void initialize(VkCommandBuffer commands, ByteBuffer parameters) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barriers = VkImageMemoryBarrier.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) {
                var barrier = barriers.get(i).sType$Default().image(images[i].image)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, null, barriers);
        }
        dispatch(commands, parameters, 0, AtmosphericSkyGenerator.LUT_WIDTH, AtmosphericSkyGenerator.LUT_HEIGHT);
        barrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    void dispatch(VkCommandBuffer commands, ByteBuffer parameters, int pass, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descriptorSet), null);
            vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, parameters);
            vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 80, stack.ints(pass));
            vkCmdDispatch(commands, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    void copyImage(VkCommandBuffer commands, int imageIndex, Readback destination) {
        Image source = images[imageIndex];
        barrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var copy = VkBufferImageCopy.calloc(1, stack);
            copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.imageExtent().set(source.width, source.height, 1);
            vkCmdCopyImageToBuffer(commands, source.image, VK_IMAGE_LAYOUT_GENERAL, destination.buffer, copy);
        }
        barrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
    }

    static void barrier(VkCommandBuffer commands, int fromStage, int fromAccess, int toStage, int toAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(fromAccess).dstAccessMask(toAccess);
            vkCmdPipelineBarrier(commands, fromStage, toStage, 0, barrier, null, null);
        }
    }

    private final class Image implements Destroyable {
        final int width, height;
        long image, allocation, view;

        Image(int width, int height, int format, boolean sampled) {
            this.width = width;
            this.height = height;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var properties = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), format, properties);
                int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
                if (sampled) required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
                if ((properties.optimalTilingFeatures() & required) != required) {
                    throw new IllegalStateException("Vulkan cannot use atmospheric sky image format " + format);
                }
                var create = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                        .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                | (sampled ? VK_IMAGE_USAGE_SAMPLED_BIT : 0));
                create.extent().set(width, height, 1);
                var handle = stack.mallocLong(1);
                var memory = stack.mallocPointer(1);
                check(vmaCreateImage(owner.vma(), create, VmaAllocationCreateInfo.calloc(stack)
                        .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE), handle, memory, null), "allocate sky image");
                image = handle.get(0);
                allocation = memory.get(0);
                var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device, viewInfo, null, handle), "create sky image view");
                view = handle.get(0);
            } catch (Throwable failure) {
                destroy();
                throw failure;
            }
        }

        @Override
        public void destroy() {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (image != 0) vmaDestroyImage(owner.vma(), image, allocation);
            view = image = allocation = 0;
        }
    }

    final class Readback implements Destroyable {
        long buffer, allocation;
        ByteBuffer mapped;

        Readback(int bytes) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var handle = stack.mallocLong(1);
                var memory = stack.mallocPointer(1);
                check(vmaCreateBuffer(owner.vma(), VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(bytes).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                        VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                                .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT)
                                .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
                        handle, memory, null), "allocate sky readback");
                buffer = handle.get(0);
                allocation = memory.get(0);
                check(vmaMapMemory(owner.vma(), allocation, memory), "map sky readback");
                mapped = MemoryUtil.memByteBuffer(memory.get(0), bytes);
            } catch (Throwable failure) {
                destroy();
                throw failure;
            }
        }

        @Override
        public void destroy() {
            if (mapped != null) vmaUnmapMemory(owner.vma(), allocation);
            if (buffer != 0) vmaDestroyBuffer(owner.vma(), buffer, allocation);
            mapped = null;
            buffer = allocation = 0;
        }
    }

    @Override
    public void destroy() {
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        for (Image image : images) if (image != null) image.destroy();
        for (Readback readback : readbacks) if (readback != null) readback.destroy();
        pipeline = pipelineLayout = descriptorPool = descriptorLayout = sampler = 0;
    }

    static void check(int result, String action) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan failed to " + action + " (" + result + ")");
    }
}
