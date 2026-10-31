#version 330
// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

#ifdef REMIX_SNOW
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
uniform sampler2D Sampler2;
#endif

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

layout(std140) uniform WeatherMatrices {
    mat4 ModelViewMat;
    mat4 ProjMat;
    vec4 WeatherViewport;
};

out vec2 texCoord;
out vec4 vertexColor;
out vec3 viewPosition;
#ifdef REMIX_SNOW
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

void main() {
    viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * vec4(viewPosition, 1.0);
    texCoord = UV0;
    vertexColor = Color;
#ifdef REMIX_SNOW
    vertexColor *= sample_lightmap(Sampler2, UV2);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
#endif
}
