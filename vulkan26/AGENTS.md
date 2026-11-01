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

Settings open from the game's Options screen, without a menu hotkey. The flat,
categorized screen uses `SettingsDraft`: reset and undo modify only the draft;
Apply batches native writes, terrain/material rebuilds, and checked persistence.
Keep English and Simplified Chinese labels, choices, and tips in sync. DLSS model
and frame generation are startup preferences, never live device reconfiguration.
Native overlay input capture remains active for menus shown by the runtime.

Volumetric clouds use `CloudVolumeResources` and `cloud_volume.comp` on Minecraft's
Vulkan encoder. Composite the same-frame volume/depth after Remix Submit and
before weather and the HUD; never modify the shared Remix image in the cloud pass.
Retire descriptors and images through the encoder's GPU lifetime. The volume
covers primary rays, not native reflections or ground shadows; legacy mesh clouds
remain selectable and are the failure fallback. The headless cloud GPU fixture
exercises production resources but does not replace signed-in Windows validation.
Do not copy code or assets from the all-rights-reserved ITRP reference pack.

`/rtx diagnoseui` records paired images around the GUI draw, before native Present.
Treat it as an opt-in diagnostic, not a fix or evidence that native temporal
history excludes the HUD. Rendering-order JVM tests cannot prove that boundary.

Preserve the parent's resource ownership, generation checks, retained assets,
terrain budgets, and reference counts. Adapt policies to 26.2's actual readers:
vanilla terrain workers still need immutable client-light snapshots.

The user requested that tests run after the complete port is implemented.
During migration use source/API inspection and compilation; defer test execution.
Final verification includes the JVM suite, both native bridges, packaged payload
checks, and a signed-in Minecraft 26.2 Fabric client on Windows with an RTX GPU
and the Vulkan backend selected. Compilation alone does not establish parity.

Keep implementation plans and migration checklists outside the repository.
