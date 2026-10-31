/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopperBeaconResourcesTest {
    private static final Identifier BEACON =
            Identifier.fromNamespaceAndPath("rtx-remix-renderer", "copper_beacon");

    @BeforeAll
    static void registerVanillaCodecs() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 26.2 binds item components after the registries and their defaults exist.
        var registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(components -> components.apply());
        ItemModels.bootstrap();
    }

    @Test
    void itemDefinitionResolvesTheCompleteBeaconModel() throws IOException {
        Identifier file = FileToIdConverter.json("items").idToFile(BEACON);
        ClientItem item = ClientItem.CODEC.parse(JsonOps.INSTANCE,
                readJson(assetPath(file))).getOrThrow();
        var model = assertInstanceOf(CuboidItemModelWrapper.Unbaked.class, item.model());
        assertEquals(BEACON.withPrefix("item/"), model.model());

        CuboidModel itemModel = readModel(model.model());
        assertEquals(BEACON.withPath("block/copper_beacon_complete"), itemModel.parent());
        assertNotNull(readModel(itemModel.parent()).geometry());
    }

    @Test
    void shippedRecipePatternMatchesTheParentIngredients() throws IOException {
        JsonObject json = readJson("data/rtx-remix-renderer/recipe/copper_beacon.json");
        var ops = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                .createSerializationContext(JsonOps.INSTANCE);
        var pattern = ShapedRecipePattern.MAP_CODEC.codec().parse(ops, json).getOrThrow();

        assertEquals(3, pattern.width());
        assertEquals(3, pattern.height());
        assertTrue(pattern.matches(input(Items.LANTERN)));
        assertFalse(pattern.matches(input(Items.SOUL_LANTERN)));
        assertEquals("minecraft:crafting_shaped", json.get("type").getAsString());
        assertEquals(BEACON.toString(), json.getAsJsonObject("result").get("id").getAsString());
        assertEquals(1, json.getAsJsonObject("result").get("count").getAsInt());
    }

    private static CraftingInput input(Item center) {
        return CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.GLASS_PANE), new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.GLASS_PANE), new ItemStack(center), new ItemStack(Items.GLASS_PANE),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.COPPER_INGOT)));
    }

    private static String assetPath(Identifier file) {
        return "assets/" + file.getNamespace() + "/" + file.getPath();
    }

    private static CuboidModel readModel(Identifier id) throws IOException {
        String path = assetPath(FileToIdConverter.json("models").idToFile(id));
        try (var reader = reader(path)) {
            return CuboidModel.fromStream(reader);
        }
    }

    private static JsonObject readJson(String path) throws IOException {
        try (var reader = reader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static InputStreamReader reader(String path) {
        var stream = CopperBeaconResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path);
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
