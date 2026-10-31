# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [ValidateSet('win64', 'arm64', 'all')]
    [string]$Architecture = 'win64'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$installerDirectory = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer'
$vswhere = Join-Path $installerDirectory 'vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
    throw 'Visual Studio Installer is missing. Use the windows-2022 runner.'
}

# Match build_native.bat's selection so preparation and compilation use the same installation.
$visualStudioPath = & $vswhere -latest -products '*' -property installationPath
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($visualStudioPath)) {
    throw 'No Visual Studio installation was found.'
}
$visualStudioPath = $visualStudioPath.Trim()
$vcvarsall = Join-Path $visualStudioPath 'VC\Auxiliary\Build\vcvarsall.bat'
if (-not (Test-Path -LiteralPath $vcvarsall -PathType Leaf)) {
    throw 'Visual Studio C++ tools are missing. Use the windows-2022 runner.'
}
Write-Host "Using Visual Studio at $visualStudioPath"

if ($Architecture -eq 'win64') {
    # The runner includes x64 tools; build_native.bat selects the default toolset.
    return
}

function Test-Arm64Toolset {
    # build_native.bat passes -vcvars_ver=14.44 for ARM64 cross-compilation.
    $toolsetRoot = Join-Path $visualStudioPath 'VC\Tools\MSVC'
    $toolsets = @(Get-ChildItem -LiteralPath $toolsetRoot -Directory |
        Where-Object { $_.Name -like '14.44.*' })
    foreach ($toolset in $toolsets) {
        $compiler = Join-Path $toolset.FullName 'bin\Hostx64\arm64\cl.exe'
        $crtLibrary = Join-Path $toolset.FullName 'lib\arm64\libcmt.lib'
        if ((Test-Path -LiteralPath $compiler -PathType Leaf) -and
            (Test-Path -LiteralPath $crtLibrary -PathType Leaf)) {
            Write-Host "ARM64 compiler and CRT found in MSVC $($toolset.Name)"
            return $true
        }
    }
    return $false
}

if (Test-Arm64Toolset) {
    return
}

# VS 2022 17.14 supplies MSVC 14.44. Add its ARM64 tools when the hosted image
# omits them, then verify the pinned toolset instead of accepting another version.
Write-Host 'Installing Visual Studio 2022 ARM64 C++ tools...'
$installer = Join-Path $installerDirectory 'setup.exe'
$installerArguments = @(
    'modify', '--installPath', ('"{0}"' -f $visualStudioPath),
    '--add', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
    '--add', 'Microsoft.VisualStudio.Component.VC.Tools.ARM64',
    '--quiet', '--norestart'
)
$installation = Start-Process -FilePath $installer -ArgumentList $installerArguments -Wait -PassThru
if ($installation.ExitCode -notin @(0, 3010)) {
    throw "Visual Studio Installer failed with exit code $($installation.ExitCode)."
}
if (-not (Test-Arm64Toolset)) {
    throw 'MSVC 14.44 ARM64 compiler/CRT is still missing. Install the VS 2022 17.14 ARM64 C++ tools required by build_native.bat.'
}
