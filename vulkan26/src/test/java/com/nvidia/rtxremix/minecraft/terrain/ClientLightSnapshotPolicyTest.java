/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshotPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLightSnapshotPolicyTest {

    @Test
    void captureSkipsClientRebuildNotificationsButStillPublishesImmutableLayers() {
        ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(true, true);
        assertEquals(Decision.LAYERED_SKIP_NOTIFICATIONS, policy.onPublish(true, false));
        assertEquals(Decision.LAYERED_SKIP_NOTIFICATIONS, policy.onPublish(true, false));
    }

    @Test
    void togglingCaptureRestoresNotificationsWithoutChangingTheSnapshotStrategy() {
        ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(true, true);
        assertEquals(Decision.LAYERED, policy.onPublish(false, false));
        assertEquals(Decision.LAYERED_SKIP_NOTIFICATIONS, policy.onPublish(true, false));
        assertEquals(Decision.LAYERED, policy.onPublish(false, false));
        assertEquals(Decision.LAYERED_SKIP_NOTIFICATIONS, policy.onPublish(true, false));
    }

    @Test
    void aVanillaBuildInFlightKeepsNotificationsUntilItFinishes() {
        ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(true, true);
        assertEquals(Decision.LAYERED, policy.onPublish(true, true));
        assertEquals(Decision.LAYERED_SKIP_NOTIFICATIONS, policy.onPublish(true, false));
        assertEquals(Decision.LAYERED, policy.onPublish(true, true));
    }

    @Test
    void serverReadersAlwaysReceiveImmutableLayersAndNotifications() {
        ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(false, true);
        for (boolean capture : new boolean[]{false, true}) {
            for (boolean buildInFlight : new boolean[]{false, true}) {
                assertEquals(Decision.LAYERED, policy.onPublish(capture, buildInFlight));
            }
        }
    }

    @Test
    void killSwitchKeepsVanillaSnapshotsAndNotificationsOnBothEngines() {
        for (boolean client : new boolean[]{false, true}) {
            ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(client, false);
            for (boolean capture : new boolean[]{false, true}) {
                for (boolean buildInFlight : new boolean[]{false, true}) {
                    assertEquals(Decision.VANILLA, policy.onPublish(capture, buildInFlight));
                }
            }
        }
    }

    @Test
    void killSwitchPropertyOnlyDisablesWhenTrue() {
        assertTrue(ClientLightSnapshotPolicy.layeredAllowedFrom(null));
        assertTrue(ClientLightSnapshotPolicy.layeredAllowedFrom("false"));
        assertTrue(ClientLightSnapshotPolicy.layeredAllowedFrom(""));
        assertFalse(ClientLightSnapshotPolicy.layeredAllowedFrom("true"));
        assertFalse(ClientLightSnapshotPolicy.layeredAllowedFrom("TRUE"));
    }
}
