/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Standalone, headless fixture using the production compute resources and shader.
 * Runs on Vulkan hardware or Mesa lavapipe; it does not launch or emulate Minecraft.
 */
public final class CloudVolumeGpuFixture implements AutoCloseable {
    private static final int WIDTH = 384, HEIGHT = 216;
    private VkInstance instance;
    private VkDevice device;
    private VkQueue queue;
    private long allocator, commandPool, fence;
    private VkCommandBuffer commands;
    private TestImage color, depth;
    private HostBuffer upload, readback;
    private CloudVolumeResources resources;
    private boolean inputsInitialized;
    private final int depthWidth, depthHeight;

    private CloudVolumeGpuFixture() {
        this(WIDTH, HEIGHT);
    }

    private CloudVolumeGpuFixture(int depthWidth, int depthHeight) {
        this.depthWidth = depthWidth;
        this.depthHeight = depthHeight;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            var application = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_2)
                    .pApplicationName(stack.UTF8("RTX cloud volume fixture"));
            check(vkCreateInstance(VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application), null, pointer));
            instance = new VkInstance(pointer.get(0), VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application));
            var count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null));
            if (count.get(0) == 0) throw new IllegalStateException("No Vulkan device for cloud fixture");
            var physicals = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, physicals));
            var physical = new VkPhysicalDevice(physicals.get(0), instance);
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            System.out.println("Cloud fixture device: " + properties.deviceNameString());
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int family = -1;
            for (int i = 0; i < families.capacity(); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { family = i; break; }
            }
            if (family < 0) throw new IllegalStateException("No Vulkan compute queue");
            var queues = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(stack.floats(1));
            var createDevice = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues);
            check(vkCreateDevice(physical, createDevice, null, pointer));
            device = new VkDevice(pointer.get(0), physical, createDevice);
            vkGetDeviceQueue(device, family, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            var functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
            check(vmaCreateAllocator(VmaAllocatorCreateInfo.calloc(stack).physicalDevice(physical).device(device)
                    .instance(instance).vulkanApiVersion(VK_API_VERSION_1_2).pVulkanFunctions(functions), pointer));
            allocator = pointer.get(0);
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle));
            commandPool = handle.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer));
            commands = new VkCommandBuffer(pointer.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle));
            fence = handle.get(0);
            color = new TestImage(VK_FORMAT_R32G32B32A32_SFLOAT, WIDTH, HEIGHT);
            depth = new TestImage(VK_FORMAT_R32_SFLOAT, depthWidth, depthHeight);
            upload = new HostBuffer(WIDTH * HEIGHT * 16 + depthWidth * depthHeight * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            readback = new HostBuffer(WIDTH * HEIGHT * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            resources = new CloudVolumeResources(device, allocator, VolumetricCloudRenderer.loadShader(), WIDTH, HEIGHT, color.view, depth.view);
        }
    }

    private float[] render(ByteBuffer parameters, boolean occluder) {
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            boolean blocked = occluder && x >= WIDTH / 3 && x < WIDTH / 2 && y >= HEIGHT / 4;
            int offset = (y * WIDTH + x) * 16;
            upload.mapped.putFloat(offset, blocked ? 0.20f : 0.24f);
            upload.mapped.putFloat(offset + 4, blocked ? 0.28f : 0.48f);
            upload.mapped.putFloat(offset + 8, blocked ? 0.12f : 0.76f);
            upload.mapped.putFloat(offset + 12, 1);
        }
        for (int y = 0; y < depthHeight; y++) for (int x = 0; x < depthWidth; x++) {
            boolean blocked = occluder && x >= depthWidth / 3 && x < depthWidth / 2 && y >= depthHeight / 4;
            putDepth(x, y, blocked);
        }
        return dispatch(parameters);
    }

    private void putDepth(int x, int y, boolean blocked) {
        float projectedDepth = blocked ? (1000f - 50f / 48f) / 999.95f : 1;
        upload.mapped.putFloat(WIDTH * HEIGHT * 16 + (y * depthWidth + x) * 4, projectedDepth);
    }

    /** A stable antialiased roof with subpixel jitter only in its render-resolution depth. */
    private float[] renderSilhouette(ByteBuffer parameters, float jitterX, float jitterY) {
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            float coverage = silhouetteCoverage(x + 0.5f, y + 0.5f);
            int offset = (y * WIDTH + x) * 16;
            upload.mapped.putFloat(offset, 0.24f + coverage * (0.20f - 0.24f));
            upload.mapped.putFloat(offset + 4, 0.48f + coverage * (0.28f - 0.48f));
            upload.mapped.putFloat(offset + 8, 0.76f + coverage * (0.12f - 0.76f));
            upload.mapped.putFloat(offset + 12, 1);
        }
        for (int y = 0; y < depthHeight; y++) for (int x = 0; x < depthWidth; x++) {
            float fullX = (x + 0.5f + jitterX) * WIDTH / depthWidth;
            float fullY = (y + 0.5f + jitterY) * HEIGHT / depthHeight;
            putDepth(x, y, silhouetteCoverage(fullX, fullY) >= 0.5f);
        }
        return dispatch(parameters);
    }

    private static float silhouetteCoverage(float x, float y) {
        float edge = WIDTH * 0.40f + (y - HEIGHT * 0.5f) * 0.37f;
        return Math.clamp(edge - x + 0.5f, 0f, 1f);
    }

    private float[] dispatch(ByteBuffer parameters) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(commands, 0));
            check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()));
            var images = VkImageMemoryBarrier.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                var barrier = images.get(i).sType$Default().image(i == 0 ? color.image : depth.image)
                        .oldLayout(inputsInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).srcAccessMask(VK_ACCESS_MEMORY_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, images);
            var region = VkBufferImageCopy.calloc(1, stack);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.imageExtent().set(WIDTH, HEIGHT, 1);
            vkCmdCopyBufferToImage(commands, upload.buffer, color.image, VK_IMAGE_LAYOUT_GENERAL, region);
            region.bufferOffset((long) WIDTH * HEIGHT * 16);
            region.imageExtent().set(depthWidth, depthHeight, 1);
            vkCmdCopyBufferToImage(commands, upload.buffer, depth.image, VK_IMAGE_LAYOUT_GENERAL, region);
            resources.record(commands, parameters);
            region.bufferOffset(0);
            region.imageExtent().set(WIDTH, HEIGHT, 1);
            vkCmdCopyImageToBuffer(commands, resources.outputImage(), VK_IMAGE_LAYOUT_GENERAL, readback.buffer, region);
            CloudVolumeResources.barrier(commands, stack);
            check(vkEndCommandBuffer(commands));
            check(vkResetFences(device, fence));
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands)), fence));
            check(vkWaitForFences(device, fence, true, 30_000_000_000L));
            inputsInitialized = true;
        }
        float[] pixels = new float[WIDTH * HEIGHT * 4];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = Float.float16ToFloat(readback.mapped.getShort(i * 2));
            if (!Float.isFinite(pixels[i])) throw new AssertionError("Non-finite cloud output at " + i);
        }
        return pixels;
    }

    static ByteBuffer parameters(float cameraX, float cameraY, float pitch, float coverage) {
        ByteBuffer parameters = BufferUtils.createByteBuffer(128);
        float c = (float) Math.cos(pitch), s = (float) Math.sin(pitch);
        float[] values = {
                cameraX, cameraY, 300, 192,
                0, -s, c, 0.65f,
                -1, 0, 0, (float) WIDTH / HEIGHT,
                0, c, s, 72,
                96, coverage, 1, 1,
                0.5f, 0.8f, 0.3316625f, 0.65f,
                1, 1, 1, 0,
                0.8f, 1, -64, 0
        };
        parameters.asFloatBuffer().put(values);
        return parameters;
    }

    private static void save(Path folder, String name, float[] pixels) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int i = (y * WIDTH + x) * 4;
            int rgb = (channel(pixels[i]) << 16) | (channel(pixels[i + 1]) << 8) | channel(pixels[i + 2]);
            image.setRGB(x, HEIGHT - 1 - y, rgb);
        }
        ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
    }
    private static int channel(float value) { return Math.clamp(Math.round(value * 255), 0, 255); }
    private static int changed(float[] before, float[] after, int minY, int maxY) {
        int changed = 0;
        for (int y = minY; y < maxY; y++) for (int x = 0; x < WIDTH; x++) {
            int i = (y * WIDTH + x) * 4;
            if (Math.abs(before[i] - after[i]) + Math.abs(before[i+1] - after[i+1]) + Math.abs(before[i+2] - after[i+2]) > 0.03f) changed++;
        }
        return changed;
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        try (var fixture = new CloudVolumeGpuFixture()) {
            float[] clear = fixture.render(parameters(100, 64, 0, 0), false);
            for (int i = 0; i < clear.length; i += 4) {
                if (Math.abs(clear[i] - 0.24f) > 0.001f || Math.abs(clear[i+1] - 0.48f) > 0.001f
                        || Math.abs(clear[i+2] - 0.76f) > 0.001f) throw new AssertionError("Zero coverage changed the scene");
            }
            float[] below = fixture.render(parameters(100, 64, 0, 0.55f), false);
            float[] repeat = fixture.render(parameters(100, 64, 0, 0.55f), false);
            if (!java.util.Arrays.equals(below, repeat)) throw new AssertionError("Static clouds changed between frames");
            int sky = changed(clear, below, HEIGHT / 2, HEIGHT);
            int horizon = changed(clear, below, HEIGHT / 2, HEIGHT / 2 + 4);
            if (sky < WIDTH * HEIGHT / 20 || horizon < WIDTH / 4) throw new AssertionError("Clouds missing from sky/horizon: " + sky + "/" + horizon);
            float[] inside = fixture.render(parameters(100, 240, 0, 0.75f), false);
            float[] above = fixture.render(parameters(100, 450, 0.42f, 0.65f), false);
            float[] moved = fixture.render(parameters(180, 64, 0, 0.55f), false);
            if (changed(below,moved,HEIGHT / 2,HEIGHT) == 0) throw new AssertionError("Camera translation did not change the volume");
            if (changed(clear,inside,0,HEIGHT) == 0 || changed(clear,above,0,HEIGHT) == 0) throw new AssertionError("Inside/above cloud view is empty");
            float[] blocked = fixture.render(parameters(100, 64, 0, 0.55f), true);
            for (int y = HEIGHT / 4; y < HEIGHT; y++) for (int x = WIDTH / 3; x < WIDTH / 2; x++) {
                int i = (y * WIDTH + x) * 4;
                if (Math.abs(blocked[i] - 0.2f) > 0.001f || Math.abs(blocked[i+1] - 0.28f) > 0.001f
                        || Math.abs(blocked[i+2] - 0.12f) > 0.001f) throw new AssertionError("Clouds painted over foreground at " + x + "," + y);
            }
            save(output,"below-and-horizon",below);
            save(output,"inside",inside);
            save(output,"above",above);
            save(output,"camera-moved",moved);
            save(output,"depth-occlusion",blocked);
            System.out.println("PASS: coverage, static repeat, horizon (" + horizon + " pixels), inside, above, camera translation and foreground occlusion");
        }
        for (int divisor : new int[]{1, 2, 3}) try (var fixture = new CloudVolumeGpuFixture(WIDTH / divisor, HEIGHT / divisor)) {
            for (float jitterY : new float[]{-0.49f, 0.49f}) for (float jitterX : new float[]{-0.49f, 0.49f}) {
                float[] result = fixture.renderSilhouette(parameters(100, 64, 0, 0.75f), jitterX, jitterY);
                save(output, "silhouette-" + divisor + "-" + jitterX + "-" + jitterY, result);
                int cloudPixels = 0;
                for (int y = HEIGHT / 2; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                    int offset = (y * WIDTH + x) * 4;
                    float coverage = silhouetteCoverage(x + 0.5f, y + 0.5f);
                    float red = 0.24f + coverage * (0.20f - 0.24f);
                    float green = 0.48f + coverage * (0.28f - 0.48f);
                    float blue = 0.76f + coverage * (0.12f - 0.76f);
                    float error = Math.abs(result[offset] - red) + Math.abs(result[offset + 1] - green)
                            + Math.abs(result[offset + 2] - blue);
                    if (coverage > 0 && error > 0.003f) {
                        throw new AssertionError("Clouds changed reconstructed silhouette at " + x + "," + y
                                + " with depth scale 1/" + divisor + " and jitter " + jitterX + "," + jitterY + ": " + error);
                    }
                    if (coverage == 0 && error > 0.03f) cloudPixels++;
                }
                if (cloudPixels < WIDTH * HEIGHT / 20) throw new AssertionError("Silhouette protection removed the sky clouds");
            }
            System.out.println("PASS: reconstructed silhouettes survive depth jitter at scale 1/" + divisor
                    + "; sky clouds remain visible");
        }
    }

    private final class TestImage implements AutoCloseable {
        long image, allocation, view;
        TestImage(int format, int width, int height) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var create = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                        .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                create.extent().set(width,height,1);
                var handle = stack.mallocLong(1);
                var pointer = stack.mallocPointer(1);
                check(vmaCreateImage(allocator,create,VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO),handle,pointer,null));
                image = handle.get(0); allocation = pointer.get(0);
                var createView = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                createView.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device,createView,null,handle));
                view = handle.get(0);
            }
        }
        @Override public void close() { vkDestroyImageView(device,view,null); vmaDestroyImage(allocator,image,allocation); }
    }
    private final class HostBuffer implements AutoCloseable {
        long buffer, allocation;
        ByteBuffer mapped;
        HostBuffer(int bytes, int usage) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var handle = stack.mallocLong(1);
                var pointer = stack.mallocPointer(1);
                check(vmaCreateBuffer(allocator,VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes).usage(usage),
                        VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                                .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT)
                                .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),handle,pointer,null));
                buffer = handle.get(0); allocation = pointer.get(0);
                check(vmaMapMemory(allocator,allocation,pointer));
                mapped = MemoryUtil.memByteBuffer(pointer.get(0),bytes);
            }
        }
        @Override public void close() { vmaUnmapMemory(allocator,allocation); vmaDestroyBuffer(allocator,buffer,allocation); }
    }
    @Override public void close() {
        if (device != null) vkDeviceWaitIdle(device);
        if (resources != null) resources.destroy();
        if (color != null) color.close();
        if (depth != null) depth.close();
        if (upload != null) upload.close();
        if (readback != null) readback.close();
        if (fence != 0) vkDestroyFence(device,fence,null);
        if (commandPool != 0) vkDestroyCommandPool(device,commandPool,null);
        if (allocator != 0) vmaDestroyAllocator(allocator);
        if (device != null) vkDestroyDevice(device,null);
        if (instance != null) vkDestroyInstance(instance,null);
    }
    private static void check(int result) { CloudVolumeResources.check(result,"run cloud fixture"); }
}
