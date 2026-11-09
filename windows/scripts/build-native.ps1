[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$SkipJavaSmoke,
    [switch]$NoBootstrap
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $IsWindows) {
    throw 'AMDL native builds must run on Windows.'
}
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required. Run this script with pwsh.exe.'
}

$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ToolsRoot = Join-Path $Root '.tools'
$Downloads = Join-Path $ToolsRoot 'downloads'
$CargoHome = Join-Path $ToolsRoot 'cargo'
$RustupHome = Join-Path $ToolsRoot 'rustup'
$CargoBin = Join-Path $CargoHome 'bin'
$LlvmRoot = Join-Path $ToolsRoot 'llvm-mingw'
$Manifest = Join-Path $Root 'rust\Cargo.toml'
$JniSmokeSource = Join-Path $Root 'rust\jni-smoke\src\com\kyant\amdl\engine\RustLibSmoke.java'
$ResourceDir = Join-Path $Root 'src\main\resources\native\windows-x64'
$ResourceDll = Join-Path $ResourceDir 'amdl.dll'
$Toolchain = 'stable-x86_64-pc-windows-gnu'
$Target = 'x86_64-pc-windows-gnu'

$RustupUri = 'https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-gnu/rustup-init.exe'
$RustupShaUri = "$RustupUri.sha256"
$LlvmVersion = '20261006'
$LlvmUri = "https://github.com/mstorsjo/llvm-mingw/releases/download/$LlvmVersion/llvm-mingw-$LlvmVersion-ucrt-x86_64.zip"
$LlvmSha256 = '317492c456aa27ee607a5919f1d2d38dcdc1112516a24d0bf4b00d078f52d17a'

$env:CARGO_HOME = $CargoHome
$env:RUSTUP_HOME = $RustupHome

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Download-File {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$OutFile
    )
    if (-not (Test-Path -LiteralPath $OutFile)) {
        Write-Host "Downloading $Uri"
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $OutFile
    }
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Label
    )
    $actual = Get-Sha256 -Path $Path
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "$Label SHA-256 mismatch. Expected $Expected but got $actual."
    }
}

function Find-LlvmBin {
    if (-not (Test-Path -LiteralPath $LlvmRoot)) {
        return $null
    }
    $driver = Get-ChildItem -LiteralPath $LlvmRoot -Recurse -File -Filter 'x86_64-w64-mingw32-gcc.exe' |
        Select-Object -First 1
    if ($null -eq $driver) {
        return $null
    }
    return $driver.Directory.FullName
}

function Bootstrap-Toolchain {
    New-Item -ItemType Directory -Force -Path $ToolsRoot, $Downloads, $CargoHome, $RustupHome | Out-Null

    $rustup = Join-Path $CargoBin 'rustup.exe'
    if (-not (Test-Path -LiteralPath $rustup)) {
        $init = Join-Path $Downloads 'rustup-init-gnu.exe'
        Download-File -Uri $RustupUri -OutFile $init
        $checksumResponse = Invoke-WebRequest -UseBasicParsing -Uri $RustupShaUri
        $checksumText = [string]$checksumResponse.Content
        $expected = ($checksumText.Trim() -split '\s+')[0]
        Assert-Sha256 -Path $init -Expected $expected -Label 'rustup-init'

        Write-Host "Installing $Toolchain under $ToolsRoot"
        & $init -y --no-modify-path --profile minimal --default-host $Target --default-toolchain $Toolchain
        if ($LASTEXITCODE -ne 0) {
            throw "rustup-init failed with exit code $LASTEXITCODE."
        }
    }

    $installedToolchains = & $rustup toolchain list
    if (($installedToolchains -join [Environment]::NewLine) -notmatch [regex]::Escape($Toolchain)) {
        & $rustup toolchain install $Toolchain --profile minimal
        if ($LASTEXITCODE -ne 0) {
            throw "rustup toolchain install failed with exit code $LASTEXITCODE."
        }
    }
    & $rustup component add rust-mingw
    if ($LASTEXITCODE -ne 0) {
        throw "Installing rust-mingw failed with exit code $LASTEXITCODE."
    }

    $llvmBin = Find-LlvmBin
    if ($null -eq $llvmBin) {
        $archive = Join-Path $Downloads "llvm-mingw-$LlvmVersion-ucrt-x86_64.zip"
        Download-File -Uri $LlvmUri -OutFile $archive
        Assert-Sha256 -Path $archive -Expected $LlvmSha256 -Label 'LLVM-MinGW archive'
        New-Item -ItemType Directory -Force -Path $LlvmRoot | Out-Null
        Write-Host "Extracting LLVM-MinGW $LlvmVersion"
        Expand-Archive -LiteralPath $archive -DestinationPath $LlvmRoot -Force
        $llvmBin = Find-LlvmBin
        if ($null -eq $llvmBin) {
            throw 'LLVM-MinGW extraction finished but x86_64-w64-mingw32-gcc.exe was not found.'
        }
    }
}

if (-not (Test-Path -LiteralPath $Manifest)) {
    throw "Rust manifest not found: $Manifest"
}

$cargo = Join-Path $CargoBin 'cargo.exe'
$rustc = Join-Path $CargoBin 'rustc.exe'
$llvmBin = Find-LlvmBin
if ((Test-Path -LiteralPath $cargo) -and ($null -ne $llvmBin)) {
    & $cargo "+$Toolchain" --version | Out-Host
    & $rustc "+$Toolchain" --version | Out-Host
}

if ($NoBootstrap -and ((-not (Test-Path -LiteralPath $cargo)) -or ($null -eq $llvmBin))) {
    throw 'The local rustup or LLVM-MinGW toolchain is missing, and -NoBootstrap was specified.'
}
if ((-not (Test-Path -LiteralPath $cargo)) -or ($null -eq $llvmBin)) {
    Bootstrap-Toolchain
    $llvmBin = Find-LlvmBin
}

$driver = Join-Path $llvmBin 'x86_64-w64-mingw32-gcc.exe'
$dlltool = Join-Path $llvmBin 'dlltool.exe'
$objdump = Join-Path $llvmBin 'llvm-objdump.exe'
$readobj = Join-Path $llvmBin 'llvm-readobj.exe'
foreach ($required in @($cargo, $rustc, $driver, $dlltool, $objdump, $readobj)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required native tool was not found: $required"
    }
}

$env:CARGO_HOME = $CargoHome
$env:RUSTUP_HOME = $RustupHome
$env:PATH = "$CargoBin;$llvmBin;$env:PATH"
$selfContainedFlag = '-Clink-self-contained=yes'
if ([string]::IsNullOrWhiteSpace($env:RUSTFLAGS)) {
    $env:RUSTFLAGS = $selfContainedFlag
} elseif ($env:RUSTFLAGS -notmatch [regex]::Escape($selfContainedFlag)) {
    $env:RUSTFLAGS = "$selfContainedFlag $env:RUSTFLAGS"
}

$hostLine = & $rustc "+$Toolchain" -vV | Where-Object { $_ -like 'host:*' } | Select-Object -First 1
$targetHost = ($hostLine -split ':', 2)[1].Trim()
if ($targetHost -ne $Target) {
    throw "Expected the $Target Rust host, but rustc reports $targetHost."
}

$lockedArgs = @()
if (Test-Path -LiteralPath (Join-Path $Root 'rust\Cargo.lock')) {
    $lockedArgs += '--locked'
}

if (-not $SkipTests) {
    Write-Host 'Running Rust native tests'
    $testArgs = @("+$Toolchain", 'test', '--release', '--manifest-path', $Manifest) + $lockedArgs
    & $cargo @testArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Rust native tests failed with exit code $LASTEXITCODE."
    }
}

Write-Host "Building amdl.dll for $Target"
$buildArgs = @("+$Toolchain", 'build', '--release', '--manifest-path', $Manifest) + $lockedArgs
& $cargo @buildArgs
if ($LASTEXITCODE -ne 0) {
    throw "Rust build failed with exit code $LASTEXITCODE."
}

$builtDll = Join-Path $Root 'rust\target\release\amdl.dll'
if (-not (Test-Path -LiteralPath $builtDll)) {
    throw "Build completed without producing $builtDll."
}

$bytes = [IO.File]::ReadAllBytes($builtDll)
if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) {
    throw "The native output is not a PE file: $builtDll"
}
$peOffset = [BitConverter]::ToUInt32($bytes, 0x3c)
$machine = [BitConverter]::ToUInt16($bytes, [int]$peOffset + 4)
if ($machine -ne 0x8664) {
    throw ('Expected a Windows x64 PE image (0x8664), found 0x{0:x4}.' -f $machine)
}

$expectedExports = @(
    'createCdm',
    'releaseCdm',
    'createCdmLicenseRequest',
    'releaseCdmLicenseRequest',
    'getChallenge',
    'getContentKey',
    'decryptM4a',
    'readM4aTag',
    'releaseM4aTag',
    'setM4aStringTag',
    'setM4aReservedTag',
    'setM4aArtwork',
    'setM4aDisc',
    'setM4aTrack',
    'setM4aCompilation',
    'setM4aIsrc',
    'writeM4aTag'
) | ForEach-Object { "Java_com_kyant_amdl_engine_RustLib_$_" }

$exportDump = (& $readobj --coff-exports $builtDll) -join [Environment]::NewLine
$missingExports = @($expectedExports | Where-Object { $exportDump -notmatch [regex]::Escape($_) })
if ($missingExports.Count -ne 0) {
    throw "Missing JNI exports: $($missingExports -join ', ')"
}

$importDump = (& $objdump -p $builtDll) -join [Environment]::NewLine
$imports = [regex]::Matches($importDump, '(?m)^\s*DLL Name:\s*(\S+)\s*$') |
    ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } |
    Sort-Object -Unique
$allowedImports = @(
    'advapi32.dll',
    'bcrypt.dll',
    'kernel32.dll',
    'msvcrt.dll',
    'ntdll.dll'
)
$unexpectedImports = @($imports | Where-Object {
    ($_ -notin $allowedImports) -and ($_ -notlike 'api-ms-win-*-l1-*-*.dll')
})
if ($unexpectedImports.Count -ne 0) {
    throw "amdl.dll has unresolved non-system runtime dependencies: $($unexpectedImports -join ', ')"
}

if (-not $SkipJavaSmoke) {
    $javacCommand = $null
    $javaCommand = $null
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $javacCommand = Get-Item -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe')
        $javaCommand = Get-Item -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe')
    } else {
        $resolvedJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($null -ne $resolvedJavac) {
            $javacCommand = Get-Item -LiteralPath $resolvedJavac.Source
            $matchingJava = Join-Path $javacCommand.Directory.FullName 'java.exe'
            if (Test-Path -LiteralPath $matchingJava) {
                $javaCommand = Get-Item -LiteralPath $matchingJava
            }
        }
    }
    if (($null -eq $javacCommand) -or ($null -eq $javaCommand)) {
        Write-Warning 'Java was not found on PATH; skipping the JNI load smoke test.'
    } else {
        $smokeClasses = Join-Path $Root 'rust\target\jni-smoke-classes'
        $resolvedSmokeClasses = [IO.Path]::GetFullPath($smokeClasses)
        $resolvedRustTarget = [IO.Path]::GetFullPath((Join-Path $Root 'rust\target')).TrimEnd('\') + '\'
        if (-not $resolvedSmokeClasses.StartsWith($resolvedRustTarget, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean a JNI smoke-test directory outside rust\target.'
        }
        if (Test-Path -LiteralPath $smokeClasses) {
            Remove-Item -LiteralPath $smokeClasses -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $smokeClasses | Out-Null
        & $javacCommand.FullName -encoding UTF-8 -d $smokeClasses $JniSmokeSource
        if ($LASTEXITCODE -ne 0) {
            throw "JNI smoke-test compilation failed with exit code $LASTEXITCODE."
        }
        & $javaCommand.FullName "-Damdl.library=$builtDll" -cp $smokeClasses com.kyant.amdl.engine.RustLibSmoke
        if ($LASTEXITCODE -ne 0) {
            throw "JNI smoke test failed with exit code $LASTEXITCODE."
        }
    }
}

New-Item -ItemType Directory -Force -Path $ResourceDir | Out-Null
Copy-Item -LiteralPath $builtDll -Destination $ResourceDll -Force

$copiedHash = Get-Sha256 -Path $ResourceDll
$size = (Get-Item -LiteralPath $ResourceDll).Length
Write-Host "Built $ResourceDll"
Write-Host "Size: $size bytes"
Write-Host "SHA-256: $copiedHash"
Write-Host "Imports: $($imports -join ', ')"
