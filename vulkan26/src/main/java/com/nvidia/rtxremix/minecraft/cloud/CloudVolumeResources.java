/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/** Immutable descriptors for one pair of scene views; retire with the engine's GPU timeline. */
final class CloudVolumeResources implements Destroyable {
    static final int PARAMETER_BYTES = 128;
    static final int NOISE_SIZE = 64;
    final int width, height, depthWidth, depthHeight;
    final long sceneView, depthView;
    private final VkDevice device;
    private final long allocator;
    private final Image[] images = new Image[4];
    private long descriptorLayout, pipelineLayout, pipeline, descriptorPool, descriptorSet, sampler;
    private boolean initialized;

    CloudVolumeResources(VkDevice device, long allocator, String source, int width, int height,
                         int depthWidth, int depthHeight,
                         long sceneView, long depthView) {
        this.device = device;
        this.allocator = allocator;
        this.width = width;
        this.height = height;
        this.depthWidth = depthWidth;
        this.depthHeight = depthHeight;
        this.sceneView = sceneView;
        this.depthView = depthView;
        try {
            images[0] = new Image(NOISE_SIZE, NOISE_SIZE, NOISE_SIZE, VK_FORMAT_R8G8B8A8_UNORM);
            images[1] = new Image((width + 1) / 2, (height + 1) / 2, 1, VK_FORMAT_R16G16B16A16_SFLOAT);
            images[2] = new Image(width, height, 1, VK_FORMAT_R16G16B16A16_SFLOAT);
            images[3] = new Image(depthWidth, depthHeight, 1, VK_FORMAT_R32G32B32A32_SFLOAT);
            createPipeline(source);
            createDescriptors();
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    long outputImage() { return images[2].image; }

    private static int descriptorType(int binding) {
        return binding >= 1 && binding <= 3
                ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    }

    private void createPipeline(String source) {
        long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize();
        long result = 0, module = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (compiler == 0 || options == 0) throw new IllegalStateException("Cannot initialize cloud shader compiler");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, "cloud_volume.comp", "main", options);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("Cannot compile volumetric clouds: "
                        + (result == 0 ? "no compiler result" : shaderc_result_get_error_message(result)));
            }
            ByteBuffer binary = shaderc_result_get_bytes(result);
            if (binary == null) throw new IllegalStateException("Empty volumetric cloud shader");
            var handle = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(7, stack);
            for (int i = 0; i < 7; i++) bindings.get(i).binding(i).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).descriptorType(descriptorType(i));
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(bindings), null, handle), "create cloud descriptor layout");
            descriptorLayout = handle.get(0);
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(PARAMETER_BYTES);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, handle), "create cloud pipeline layout");
            pipelineLayout = handle.get(0);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(binary),
                    null, handle), "create cloud shader module");
            module = handle.get(0);
            var create = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
            create.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, 0, create, null, handle), "create cloud compute pipeline");
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
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT), null, handle), "create cloud sampler");
            sampler = handle.get(0);
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(sizes), null, handle), "create cloud descriptor pool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "allocate cloud descriptors");
            descriptorSet = handle.get(0);
            var writes = VkWriteDescriptorSet.calloc(7, stack);
            for (int i = 0; i < 7; i++) {
                long view = switch (i) { case 2 -> sceneView; case 3 -> depthView;
                    case 4 -> images[1].view; case 5 -> images[2].view; case 6 -> images[3].view;
                    default -> images[0].view; };
                var image = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .imageView(view).sampler(descriptorType(i) == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ? sampler : 0);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(descriptorType(i)).pImageInfo(image);
            }
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    void record(VkCommandBuffer commands, ByteBuffer parameters) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            barrier(commands, stack);
            if (!initialized) {
                var transitions = VkImageMemoryBarrier.calloc(images.length, stack);
                for (int i = 0; i < images.length; i++) {
                    var transition = transitions.get(i).sType$Default().image(images[i].image)
                            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    transition.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                }
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, null, transitions);
            }
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            if (!initialized) {
                dispatch(commands, parameters, 0, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE);
                barrier(commands, stack);
                initialized = true;
            }
            dispatch(commands, parameters, 3, depthWidth, depthHeight, 1);
            barrier(commands, stack);
            dispatch(commands, parameters, 1, (width + 1) / 2, (height + 1) / 2, 1);
            barrier(commands, stack);
            dispatch(commands, parameters, 2, width, height, 1);
            barrier(commands, stack);
        }
    }

    private void dispatch(VkCommandBuffer commands, ByteBuffer parameters, int pass, int width, int height, int depth) {
        parameters.putFloat(124, pass);
        vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, parameters);
        vkCmdDispatch(commands, (width + 7) / 8, (height + 7) / 8, depth);
    }

    static void barrier(VkCommandBuffer commands, MemoryStack stack) {
        var memory = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, memory, null, null);
    }

    private final class Image {
        long image, view, allocation;
        Image(int width, int height, int depth, int format) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var properties = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), format, properties);
                int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
                if (depth > 1) required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
                if ((properties.optimalTilingFeatures() & required) != required) {
                    throw new IllegalStateException("Unsupported volumetric cloud image format: " + format);
                }
                var create = VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(depth > 1 ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D).format(format)
                        .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
                create.extent().set(width, height, depth);
                var handle = stack.mallocLong(1);
                var memory = stack.mallocPointer(1);
                check(vmaCreateImage(allocator, create, VmaAllocationCreateInfo.calloc(stack)
                        .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE), handle, memory, null), "allocate cloud image");
                image = handle.get(0); allocation = memory.get(0);
                var createView = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                        .viewType(depth > 1 ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D).format(format);
                createView.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device, createView, null, handle), "create cloud image view");
                view = handle.get(0);
            } catch (RuntimeException | Error failure) {
                destroy();
                throw failure;
            }
        }
        void destroy() {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (image != 0) vmaDestroyImage(allocator, image, allocation);
            image = view = allocation = 0;
        }
    }

    @Override public void destroy() {
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        for (Image image : images) if (image != null) image.destroy();
        pipeline = pipelineLayout = descriptorPool = descriptorLayout = sampler = 0;
    }

    static void check(int result, String action) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan failed to " + action + " (" + result + ")");
    }
}
