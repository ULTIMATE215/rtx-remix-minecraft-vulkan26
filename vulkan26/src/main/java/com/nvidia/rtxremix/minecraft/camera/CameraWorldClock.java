/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import com.nvidia.rtxremix.minecraft.mixin.ClientClockInstanceAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ClientClockManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Applies the v1 camera schema's 24,000-tick day to 26.2's shared Overworld clock. */
final class CameraWorldClock {
    private ClientLevel level;
    private ClientClockInstanceAccessor clientClock;
    private ServerWriter serverWriter;
    private volatile long session;

    long session() {
        return session;
    }

    void reset() {
        session++;
        if (serverWriter != null) {
            serverWriter.active = false;
        }
        serverWriter = null;
        clientClock = null;
        level = null;
    }

    void prepare(Minecraft client) {
        ClientLevel nextLevel = client.level;
        if (nextLevel == null) {
            reset();
            return;
        }
        if (level != nextLevel) {
            reset();
            level = nextLevel;
            Holder<WorldClock> clock = nextLevel.registryAccess()
                    .lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
            // Ensure the vanilla instance exists, then retain it for allocation-free frame updates.
            nextLevel.clockManager().getTotalTicks(clock);
            clientClock = (ClientClockInstanceAccessor) ((ClientClockManagerAccessor)
                    nextLevel.clockManager()).rtxremix$getClocks().get(clock);
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                Holder<WorldClock> serverClock = server.registryAccess()
                        .lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
                serverWriter = new ServerWriter(server, serverClock);
            }
        }
    }

    void setTime(Minecraft client, long ticks) {
        prepare(client);
        if (clientClock == null) {
            return;
        }
        // Keep the server's pause/rate settings. Camera poses change the tick only.
        clientClock.rtxremix$setTotalTicks(ticks);
        clientClock.rtxremix$setPartialTick(0.0f);
        if (serverWriter != null) {
            serverWriter.request(ticks);
        }
    }

    /** Coalesces render-frame requests without touching server state from the client thread. */
    private static final class ServerWriter implements Runnable {
        private final MinecraftServer server;
        private final Holder<WorldClock> clock;
        private final AtomicLong requested = new AtomicLong(-1L);
        private final AtomicBoolean queued = new AtomicBoolean();
        private volatile boolean active = true;

        private ServerWriter(MinecraftServer server, Holder<WorldClock> clock) {
            this.server = server;
            this.clock = clock;
        }

        void request(long ticks) {
            requested.set(ticks);
            schedule();
        }

        private void schedule() {
            if (active && queued.compareAndSet(false, true)) {
                server.execute(this);
            }
        }

        @Override
        public void run() {
            try {
                long ticks = requested.getAndSet(-1L);
                if (active && ticks >= 0L) {
                    server.clockManager().setTotalTicks(clock, ticks);
                }
            } finally {
                queued.set(false);
                if (requested.get() >= 0L) {
                    schedule();
                }
            }
        }
    }
}
