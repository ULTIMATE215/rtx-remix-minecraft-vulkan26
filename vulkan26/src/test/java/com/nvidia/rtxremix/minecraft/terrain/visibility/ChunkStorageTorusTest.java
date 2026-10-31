/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkStorageTorusTest {
    private static final int SIZE_X = 5;
    private static final int SIZE_Y = 3;
    private static final int SIZE_Z = 5;
    private static final int BOTTOM_Y = -64;

    @Test
    void stripUpdatesMatchVanillaAcrossMovementCases() {
        assertEquivalent("first constructor-layout update", null, 173.25, -211.75);
        assertEquivalent("no horizontal movement", new Camera(-24.5, 37.5), -24.5, 37.5);
        assertEquivalent("positive one-chunk move", new Camera(0.0, 0.0), 16.0, 0.0);
        assertEquivalent("positive move just over a boundary", new Camera(0.0001, 0.0001), 16.0001, 0.0001);
        assertEquivalent("negative one-chunk move", new Camera(0.0, 0.0), -16.0, 0.0);
        assertEquivalent("negative move just over a boundary", new Camera(-0.0001, -0.0001), -16.0001, -0.0001);
        assertEquivalent("diagonal move", new Camera(0.0, 0.0), 16.0, -16.0);
        assertEquivalent("multi-chunk move", new Camera(-19.25, 33.75), 44.75, -14.25);
        assertEquivalent("diameter teleport", new Camera(0.0, 0.0), SIZE_X * 16.0, 0.0);
        assertEquivalent("beyond-diameter teleport", new Camera(0.0, 0.0), 112.0, -96.0);
        assertEquivalent("negative coordinates", new Camera(-97.01, -64.0), -128.0, -31.999);
    }

    @Test
    void targetOriginMatchesVanillaAtSectionBoundariesAndWorldLimits() {
        double[] coordinates = {
                -32.0001, -32.0, -31.9999,
                -0.0001, -0.0, 0.0001,
                15.9999, 16.0, 16.0001,
                -30_000_000.0, -29_999_984.0, 29_999_984.0, 30_000_000.0
        };
        for (double coordinate : coordinates) {
            FakeStorage vanilla = new FakeStorage(SIZE_X, SIZE_Y, SIZE_Z, BOTTOM_Y);
            vanilla.reposition(coordinate, 0.0);
            int centerSection = SectionPos.blockToSectionCoord(coordinate);
            for (int slot = 0; slot < SIZE_X; slot++) {
                assertEquals(
                        vanilla.origins.get(slot).x,
                        ChunkStorageTorus.targetOrigin(centerSection, slot, SIZE_X),
                        "coordinate=" + coordinate + ", slot=" + slot);
            }
        }
    }

    @Test
    void renderDistanceThirtyTwoTouchesOnlyEnteringPlanes() {
        int size = 65;
        int verticalSections = 24;
        FakeStorage storage = new FakeStorage(size, verticalSections, size, -64);
        ChunkStorageTorus.Workspace workspace = new ChunkStorageTorus.Workspace();
        storage.reposition(0.0, 0.0);
        storage.clearMoves();

        int moved = ChunkStorageTorus.reposition(
                storage,
                workspace,
                size,
                verticalSections,
                size,
                -64,
                1,
                0);
        assertEquals(1_560, moved);

        storage.clearMoves();
        moved = ChunkStorageTorus.reposition(
                storage,
                workspace,
                size,
                verticalSections,
                size,
                -64,
                2,
                1);
        assertEquals(3_096, moved);

        storage.clearMoves();
        moved = ChunkStorageTorus.reposition(
                storage,
                workspace,
                size,
                verticalSections,
                size,
                -64,
                2,
                1);
        assertEquals(0, moved);
        assertEquals(130, storage.originReads);
    }

    private static void assertEquivalent(
            String description,
            Camera initialCamera,
            double cameraX,
            double cameraZ) {
        FakeStorage initial = new FakeStorage(SIZE_X, SIZE_Y, SIZE_Z, BOTTOM_Y);
        if (initialCamera != null) {
            initial.reposition(initialCamera.x, initialCamera.z);
        }
        FakeStorage expected = initial.copy();
        FakeStorage actual = initial.copy();

        expected.reposition(cameraX, cameraZ);
        int moved = ChunkStorageTorus.reposition(
                actual,
                new ChunkStorageTorus.Workspace(),
                SIZE_X,
                SIZE_Y,
                SIZE_Z,
                BOTTOM_Y,
                SectionPos.blockToSectionCoord(cameraX),
                SectionPos.blockToSectionCoord(cameraZ));

        assertEquals(expected.origins, actual.origins, description + " final origins");
        assertEquals(expected.moves, actual.moves, description + " setOrigin calls");
        assertEquals(expected.moves.size(), moved, description + " moved count");
    }

    private record Camera(double x, double z) {
    }

    private record Origin(int x, int y, int z) {
    }

    private record Move(int index, Origin origin) {
    }

    private static final class FakeStorage implements ChunkStorageTorus.StorageAccess {
        private final List<Origin> origins;
        private final List<Move> moves = new ArrayList<>();
        private final RotatingSectionStorage<RotatingSectionStorage.Value> vanilla;
        private int originReads;

        private FakeStorage(int sizeX, int sizeY, int sizeZ, int bottomY) {
            origins = new ArrayList<>(sizeX * sizeY * sizeZ);
            for (int i = 0; i < sizeX * sizeY * sizeZ; i++) {
                origins.add(null);
            }
            assertEquals(sizeX, sizeZ);
            int minSectionY = SectionPos.blockToSectionCoord(bottomY);
            vanilla = new RotatingSectionStorage<>(sizeX / 2, minSectionY, minSectionY + sizeY - 1,
                    (index, section) -> {
                        origins.set(index, origin(section));
                        return new RotatingSectionStorage.Value() {
                            @Override
                            public long getSectionNode() {
                                Origin current = origins.get(index);
                                return SectionPos.asLong(current.x >> 4, current.y >> 4, current.z >> 4);
                            }

                            @Override
                            public void setSectionNode(long sectionNode) {
                                Origin next = origin(sectionNode);
                                rtxremix$setOrigin(index, next.x, next.y, next.z);
                            }
                        };
                    });
        }

        private FakeStorage copy() {
            int size = vanilla.radius() * 2 + 1;
            FakeStorage copy = new FakeStorage(size, vanilla.height(), size,
                    SectionPos.sectionToBlockCoord(vanilla.minY()));
            for (int i = 0; i < origins.size(); i++) copy.origins.set(i, origins.get(i));
            return copy;
        }

        private void reposition(double cameraX, double cameraZ) {
            vanilla.repositionCenter(SectionPos.of(SectionPos.blockToSectionCoord(cameraX), 0,
                    SectionPos.blockToSectionCoord(cameraZ)));
        }

        private static Origin origin(long section) {
            return new Origin(SectionPos.sectionToBlockCoord(SectionPos.x(section)),
                    SectionPos.sectionToBlockCoord(SectionPos.y(section)),
                    SectionPos.sectionToBlockCoord(SectionPos.z(section)));
        }

        private void clearMoves() {
            moves.clear();
            originReads = 0;
        }

        @Override
        public int rtxremix$getOriginX(int slotIndex) {
            originReads++;
            return origins.get(slotIndex).x;
        }

        @Override
        public int rtxremix$getOriginZ(int slotIndex) {
            originReads++;
            return origins.get(slotIndex).z;
        }

        @Override
        public void rtxremix$setOrigin(int slotIndex, int x, int y, int z) {
            Origin origin = new Origin(x, y, z);
            origins.set(slotIndex, origin);
            moves.add(new Move(slotIndex, origin));
        }
    }
}
