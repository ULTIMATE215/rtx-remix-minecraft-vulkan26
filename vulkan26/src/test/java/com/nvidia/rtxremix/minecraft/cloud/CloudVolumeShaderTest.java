/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

class CloudVolumeShaderTest {
    @Test void productionCloudShaderCompilesForVulkan12() {
        long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize(), result = 0;
        try {
            assertNotEquals(0,compiler); assertNotEquals(0,options);
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            result = shaderc_compile_into_spv(compiler,VolumetricCloudRenderer.loadShader(),shaderc_compute_shader,"cloud_volume.comp","main",options);
            assertNotEquals(0,result);
            assertEquals(shaderc_compilation_status_success,shaderc_result_get_compilation_status(result),shaderc_result_get_error_message(result));
            assertTrue(shaderc_result_get_length(result) > 0);
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            if (compiler != 0) shaderc_compiler_release(compiler);
        }
    }
    @Test void noiseCoordinatesWrapContinuouslyAcrossPositiveAndNegativeWorldCoordinates() {
        assertEquals(0,VolumetricCloudRenderer.periodic(0));
        assertEquals(0,VolumetricCloudRenderer.periodic(131072));
        assertEquals(131071,VolumetricCloudRenderer.periodic(-1));
        assertEquals(12.25f,VolumetricCloudRenderer.periodic(131072 * 40.0 + 12.25));
    }
    @Test void cloudParametersRejectNonFiniteAndOutOfRangeValues() {
        var settings = new CloudVolumeSettings();
        assertTrue(settings.isEnabled());
        assertEquals(72,settings.steps());
        assertThrows(IllegalArgumentException.class,() -> settings.setThickness(0));
        assertThrows(IllegalArgumentException.class,() -> settings.setCoverage(Float.NaN));
        assertThrows(IllegalArgumentException.class,() -> settings.setDensity(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,() -> settings.setQuality("unknown"));
    }
}
