# RTX Remix for Minecraft 26.2

This independent Gradle build ports the parent Minecraft 1.21.1 mod to
Minecraft 26.2, Java 25, and Minecraft's Vulkan backend. The required result
preserves the parent's features, settings, commands, resource identities,
runtime configuration, and lifecycle behavior. A reduced renderer is not an
equivalent port. The parent sources remain the behavioral reference.

Use the integrated `../rtxremix-java` facade and JNI bridge with the parent's
pinned RTX Remix runtime. Keep Minecraft and Fabric dependencies out of the
wrapper. Bundle the native runtime and author-owned payload in release JARs;
an installed parent mod or source checkout is not a runtime dependency.
Keep the copied C API, JNI, and both architecture pins aligned with a
published runtime. Do not substitute the older sibling runtime checkout.

Preserve vanilla world traversal and capture models on the render thread.
Adapt the parent's OpenGL interop to Vulkan external memory and semaphores.
Minecraft owns its Vulkan device, queues, and command buffers. Submit game-side
commands through its encoder and retire imported resources after GPU completion.
Keep a single owner of final presentation and preserve Remix's native menus.

Preserve the parent's resource ownership, generation checks, retained assets,
terrain budgets, and reference counts. Adapt policies to 26.2's actual readers:
vanilla terrain workers still need immutable client-light snapshots.

The user requested that tests run after the complete port is implemented.
During migration use source/API inspection and compilation; defer test execution.
Final verification includes the JVM suite, both native bridges, packaged payload
checks, and a signed-in Minecraft 26.2 Fabric client on Windows with an RTX GPU
and the Vulkan backend selected. Compilation alone does not establish parity.

Keep implementation plans and migration checklists outside the repository.
