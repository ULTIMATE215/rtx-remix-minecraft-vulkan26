/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the actual Minecraft call sites; native presentation/history still needs GPU verification. */
class GuiFrameOrderTest {
    private static final String GAME = "net/minecraft/client/renderer/GameRenderer";
    private static final String GUI = "net/minecraft/client/gui/render/GuiRenderer";
    private static final String EFFECTS = "net/minecraft/client/renderer/ScreenEffectRenderer";

    @Test
    void remixSubmissionAndDiagnosticHooksBracketTheActualGuiDraw() throws IOException {
        ClassNode vanilla = read(GAME);
        ClassNode mixin = read("com/nvidia/rtxremix/minecraft/mixin/GameRendererMixin");
        MethodNode render = method(vanilla, "render");
        int world = call(render, GAME, "renderLevel");
        int post = call(render, "net/minecraft/client/renderer/PostChain", "process");
        int gui = call(render, GUI, "render");
        assertTrue(world < post && post < gui, "world composition and post effects must precede the HUD");

        MethodNode level = method(vanilla, "renderLevel");
        assertTrue(call(level, GAME, "renderItemInHand") < call(level, EFFECTS, "submit"));
        assertHook(mixin, "rtxremix$submitBeforeScreenEffects", "renderLevel", "INVOKE",
                "L" + EFFECTS + ";submit(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V");
        assertHook(mixin, "rtxremix$submitAtWorldTail", "renderLevel", "RETURN", null);
        assertHook(mixin, "rtxremix$beginGuiScope", "render", "INVOKE", "L" + GUI + ";render()V");
        assertHook(mixin, "rtxremix$endGuiScope", "render", "RETURN", null);
    }

    private static int call(MethodNode method, String owner, String name) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return i;
        }
        throw new AssertionError("Missing Minecraft call site: " + owner + "." + name);
    }

    private static void assertHook(ClassNode mixin, String handler, String method, String kind, String target) {
        AnnotationNode inject = method(mixin, handler).visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .findFirst().orElseThrow();
        assertEquals(List.of(method), value(inject, "method"));
        AnnotationNode at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
        assertEquals(kind, value(at, "value"));
        assertEquals(target, value(at, "target"));
        assertNull(value(at, "shift"), "these hooks must not shift past the target draw");
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode read(String name) throws IOException {
        ClassNode type = new ClassNode();
        try (var source = GuiFrameOrderTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(source, name);
            new ClassReader(source).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return type;
    }
}
