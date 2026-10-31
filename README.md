# RTX Remix for Minecraft 26.2

The Minecraft 26.2 port lives in [`vulkan26/`](vulkan26/README.md). It targets
Java 25, Windows, and Minecraft's Vulkan backend, with the shared JNI bridge
under `rtxremix-java/` and runtime versions pinned in `packman/`.
The original Minecraft 1.21.1 project remains at the repository root as the
behavioral reference; run 26.2 Gradle commands from `vulkan26/`.

To build a complete JAR on GitHub, open **Actions → Build Vulkan26 JAR → Run
workflow**. Choose `win64`, `arm64`, or `all`, then download the JAR and its
SHA-256 checksum from the successful run's **Artifacts**. The workflow builds
the JNI bridge, fetches the pinned Remix runtime, runs the JVM tests, and
verifies the packaged payload. Building does not require an RTX GPU.

The port is still in validation. The complete JAR includes the native runtime,
but actual rendering and installation require separate validation on a
Windows RTX client. See the [module guide](vulkan26/README.md) for local build
commands, installation requirements, and current release limitations.
