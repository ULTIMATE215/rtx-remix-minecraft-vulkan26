# RTX Remix for Minecraft 26.2

This module ports the full Minecraft 1.21.1 parent mod to
Minecraft 26.2, Java 25, and Minecraft's Vulkan graphics backend. The migration
keeps the RTX Remix runtime, integrated JNI bridge, mod identity, resources,
settings, commands, and scene features.

The port is in validation. Java sources and Vulkan shaders compile, and the
full JVM suite passes against Minecraft 26.2. Windows x64 Gradle builds, JNI
compilation, JVM tests, and complete-JAR verification also pass on GitHub Actions.
Windows ARM64, installer, and real-client rendering validation remain pending.
Existing experimental
`remix-vulkan-26.2-0.13.1.jar` artifacts do not contain this full port, and their
test reports are not validation of it.

The final distributable bundles the native runtime and payload, using the
parent's first-launch extraction and file-ownership rules. It will not require
an installed 1.21.1 mod or a source checkout.

The build requires JDK 25. Native compilation and packaging require Windows
and the Visual Studio C++ toolchain. Running the client additionally requires
the parent's supported RTX hardware. Shared wrapper sources and runtime pins
come from this repository.

Compilation and JVM tests do not establish rendering parity. Native loading,
installation, and scene behavior must also be checked on the Windows client
before release.

Run build commands from this directory with JDK 25 and the Windows C++ toolchain.
`compileJava` compiles Java without running tests or staging the native runtime:

```powershell
.\gradlew.bat compileJava
```

The full build, tests, and release packaging use:

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat package -Parch=win64
.\gradlew.bat releaseArchive -Parch=win64
```

Use `-Parch=arm64` for Windows ARM64 or omit it to bundle both architectures.
The lightweight JAR is written to `build/libs/`; the self-contained JAR and
installer ZIP are written to `build/distributions/`. Native bridges build into
this module's `build/native/`. SDK inputs and texture assets use the parent's
`../remix-runtime/`, managed from the repository root with `tools\rtx.bat sdk`.
Do not build while a client using this module's staged files is running.

To build on GitHub, use [Build Vulkan26 JAR](../.github/workflows/vulkan26-build.yml).
Commit the workflow, its helper under `.github/scripts/`, and this module with
the shared repository sources. GitHub requires the workflow on the default
branch before it offers the manual **Run workflow** button.

1. Open **Actions → Build Vulkan26 JAR → Run workflow** and select the branch.
2. Choose `win64` (default), `arm64`, or `all` (both architectures in one JAR).
3. After the run succeeds, download `vulkan26-<architecture>-<run number>` from
   **Artifacts** and extract the JAR and its SHA-256 checksum.

The workflow uses a Windows runner with JDK 25 and MSVC. It runs `package`,
which includes the build, JVM tests, JNI compilation, and package-content
verification. Gradle fetches the pinned runtime and textures from NVIDIA's
public Packman service without extra secrets. The artifact contains the
self-contained JAR from `build/distributions/`, including the selected native
runtime and shipped payload. Test reports are uploaded separately, including
when tests fail; artifacts are retained for 14 days. The workflow does not
exercise GPU rendering, so client validation remains required before release.

The workflow produces the complete JAR. The separate `releaseArchive` installer
ZIP additionally needs the authoritative `NOTICE` and `THIRD_PARTY_NOTICES.md`
under this module; both are missing from the current source snapshot and must
be restored before making an installer release.

Extract the installer ZIP and run `install.bat`. It creates the **NVIDIA RTX
Remix for Minecraft 26.2** Prism instance and selects Vulkan in its game options.
Sign in to a Microsoft account that owns Minecraft: Java Edition, then launch
that instance. See [the installation guide](packaging/INSTALL.md). The root
`tools\rtx.bat launch` command still targets the 1.21.1 parent project.

For an existing Fabric 26.2 instance, install the packaged JAR and Fabric API
0.153.0+26.2, use Java 25, and select Vulkan before launching. With the game
closed, set `preferredGraphicsBackend:"vulkan"` in the instance's `options.txt`.

Minecraft can reset this preference to Default or OpenGL after an unsuccessful
startup. If the log says `Using graphics backend OpenGL`, select Vulkan again
under Video Settings, quit normally, and restart. Remix stays inactive on
OpenGL and the mod keeps vanilla terrain rendering; the startup notification
explains how to enable Vulkan. The RTX hotkey and `/rtx on` cannot enable capture
until the Remix renderer has initialized successfully.

If Vulkan starts and entities appear but terrain remains missing, the mod logs
up to six `Terrain pipeline has no captured columns` samples automatically.
These include visibility, extraction, compilation, builder-buffer availability,
and cumulative capture counts. Keep the full `logs/latest.log` after entering
the world; F3 statistics and extra JVM arguments are not required. A null pointer
inside a section task is reported with its section position and original cause
instead of being silently treated as buffer-pool exhaustion by the dispatcher.

The runtime's view-distance fade also applies to cloud geometry. Its horizontal
distance mode can leave a circular cloud patch above the camera. To disable that
clipping, close Minecraft and change `rtx.viewDistance.distanceMode = 0` in the
instance's `rtx.conf`, then restart. This also disables distant terrain fading;
the pinned runtime has no per-cloud exemption. Restore `3` to restore the shipped
fade mode. `/rtx viewdistance auto off` only restores the 32-chunk fade distances
and does not turn the fade off. This setting does not fix missing terrain captures.

World-saved zone definitions use `data/minecraft/rtx_remix_zones.dat` in 26.2.
When opening a world with the parent's `data/rtx_remix_zones.dat`, the mod
imports it before reading saved zones. It keeps the original file and uses an
existing 26.2 file in preference to the legacy copy. World conf profiles stay
under the save's `rtx-remix/confs/` directory.
