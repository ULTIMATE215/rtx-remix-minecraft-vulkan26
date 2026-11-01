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

World-saved zone definitions use `data/minecraft/rtx_remix_zones.dat` in 26.2.
When opening a world with the parent's `data/rtx_remix_zones.dat`, the mod
imports it before reading saved zones. It keeps the original file and uses an
existing 26.2 file in preference to the legacy copy. World conf profiles stay
under the save's `rtx-remix/confs/` directory.

## Settings and clouds

Open **Options → RTX Remix Settings** from the title or pause menu. The flat
settings page has category tabs, scrolling rows, and an explanation panel on
wide windows (tooltips on small windows). Simplified Chinese covers the labels,
choices, and explanations. There is no settings-opening hotkey; Alt+X no longer
opens the native menu. Automatically shown Remix overlays still capture input.

Changes stay pending until **Apply**. **Undo** restores the last applied values;
group and global resets also wait for Apply. Leaving with pending changes asks
whether to discard them. A failed apply or save stays retryable. DLSS model and
frame-generation preferences take effect on the next Minecraft launch; their
labels and descriptions indicate that a restart is required.

**Quality → Clouds → Volumetric clouds** enables a three-dimensional cloud layer
by default. Vulkan compute integrates density, light extinction, and cloud
self-shadowing through a curved atmosphere, including the distant horizon and
views from inside or above the layer. The layer follows world time and weather;
quality, thickness, coverage, density, wind speed, height, and size are adjustable.
Minecraft's own Clouds setting must also be enabled.

Clouds are composited with the same frame's scene depth after Remix reconstruction
and before weather and the HUD. Depth remains at the internal render resolution;
a conservative foreground footprint protects reconstructed silhouettes from
jittered depth misses. This can leave a narrow area without clouds next to an
occluder. The headless GPU fixture covers full-, half-, and third-resolution depth
with subpixel jitter; Windows rendering still needs visual validation.
Clouds use no temporal history and are independent
of Remix's circular geometry fade. The current volume covers the primary view;
it does **not** contribute to native reflections or shadows on terrain. Disable
Volumetric clouds to use the previous mesh clouds for those effects. Mesh clouds
retain the runtime's distance fade. A failed volume initialization logs the cause
and falls back to mesh clouds until resources are reloaded.

The cloud shader and periodic noise are independently implemented. The ITRP
reference informed the volume-rendering approach; none of its shader code or
assets is included.

## Diagnosing HUD trails

The hotbar is drawn after world submission and Minecraft's post effects. Windows
client comparisons have reported clean captures before presentation, with on-screen
trails reduced by disabling frame generation. This narrows that report to the native
frame-generation/presentation path; it does not identify its exact defect.

Run **`/rtx diagnoseui`**, close chat, and move the camera. After a two-second
delay the mod records eight pairs of images, immediately before and after HUD
drawing, under `screenshots/rtx-ui-<timestamp>/`. `render-modes.txt` records the
resolved DLSS, frame-generation, and sparse-rendering options. GPU readback and
PNG writing are asynchronous; captures stop on leaving the world or disabling
RTX. The command is opt-in and does not change graphics settings.

These images precede native presentation. Trails already visible before the HUD
point to an earlier stage; clean pairs with visible on-screen trails narrow the
problem to a later stage. A frame-generation comparison can be made under
**Performance → DLSS → Frame gen (restart)** by applying Off and restarting.
This diagnostic is not a HUD ghosting fix.

Frame generation remains supported. The pinned 0.16.0 client interface has no
separate HUD input for interpolation. The public dxvk-remix `main` checked at
`9dd9b9398cdcb600fb387cc72c36a15c8d8680a1` exposes API 0.6.6 and lacks the pinned
runtime's interop implementation, so it cannot be substituted to patch that path.

## DLSS 4.5

The pinned runtime already bundles DLSS Super Resolution 310.9.1.0, including
the DLSS 4.5 second-generation Transformer presets M and L. No replacement DLL
is needed. See NVIDIA's [L/M introduction](https://github.com/NVIDIA/DLSS/releases/tag/v310.5.0)
and [310.9.1 SDK](https://github.com/NVIDIA/DLSS/releases/tag/v310.9.1).

Choose a mode under **RTX Remix Settings → Performance → DLSS**, or use:

| Command | Selection for the next Minecraft launch |
| --- | --- |
| `/rtx dlss sr45` | DLSS 4.5 Super Resolution, preset M |
| `/rtx dlss sr45-l` | DLSS 4.5 Super Resolution, preset L |
| `/rtx dlss rr` | DLSS Ray Reconstruction, keeping its existing model preference |
| `/rtx dlss remix` | Let Remix's native settings control DLSS (default) |

Restart Minecraft after selecting a mode. Startup applies the choice before
native device creation so Remix initializes the matching lighting and denoiser
settings. `/rtx dlss` reports the runtime's resolved path and preset, followed
by the selection for the next launch. This reports configuration; a driver
override or a feature fallback can affect the model NGX ultimately executes.

M is optimized for Performance and L for Ultra Performance. The selection
preserves your DLSS quality mode; adjust it in the same settings group's
**DLSS quality** control. The RTX 4070
Laptop supports these SR models, whose GPU cost can differ from the older models.

This runtime uses SR and Ray Reconstruction as alternative paths. **Sparse
Rendering requires Ray Reconstruction**, so it is inactive in the 4.5 SR modes
even if its checkbox remains checked. The mod keeps that preference intact;
`/rtx dlss rr` and a restart restore the RR path. RR uses its own D/E/F models:
an SR M/L hint cannot upgrade RR. Frame-generation settings are unchanged.

The selection persists as `dlss.mode` in `config/rtxremix.json`. Explicit modes
apply at each launch; **Remix Menu** makes no native writes and follows whatever
the native menu has saved. It does not erase those saved settings. Existing
installations default to **Remix Menu**, preserving their current behavior.
Benchmark `RTXREMIX_EXTRA_RTXCONF` overrides are applied after this selection.
