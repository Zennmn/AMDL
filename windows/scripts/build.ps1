param([switch]$SkipNative, [switch]$RenderUi)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$distributionVersion = '1.1.6'

function Get-JdkMajorVersion {
    param([Parameter(Mandatory = $true)][string]$JdkHome)
    $releaseFile = Join-Path $JdkHome 'release'
    if (Test-Path -LiteralPath $releaseFile -PathType Leaf) {
        $releaseMatch = Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="(?<major>\d+)' -List
        if ($releaseMatch) {
            return [int]$releaseMatch.Matches[0].Groups['major'].Value
        }
    }
    return $null
}

$jdkCandidates = [System.Collections.Generic.List[string]]::new()
if ($env:JAVA_HOME) { $jdkCandidates.Add($env:JAVA_HOME) }
foreach ($root in @(
    (Join-Path $env:USERPROFILE '.gradle\jdks'),
    (Join-Path $env:LOCALAPPDATA 'Android'),
    'C:\Program Files\Eclipse Adoptium',
    'C:\Program Files\Java',
    'C:\Program Files\Microsoft',
    'C:\Program Files\Amazon Corretto',
    'C:\Program Files\Zulu',
    'C:\Program Files\BellSoft'
)) {
    if (Test-Path -LiteralPath $root -PathType Container) {
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { $jdkCandidates.Add($_.FullName) }
    }
}

$jdk = $jdkCandidates |
    Select-Object -Unique |
    Where-Object {
        (Test-Path -LiteralPath (Join-Path $_ 'bin\jpackage.exe')) -and
        ((Get-JdkMajorVersion -JdkHome $_) -eq 21)
    } |
    Select-Object -First 1
if (-not $jdk) {
    throw 'A complete x64 JDK 21 is required (including jpackage). Set JAVA_HOME to a JDK 21 directory.'
}
$env:JAVA_HOME = $jdk

Push-Location -LiteralPath $projectRoot
try {
    if (-not $SkipNative) {
        & (Join-Path $PSScriptRoot 'build-native.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'Native engine build or validation failed.' }
    }
    $nativeLibrary = Join-Path $projectRoot 'src\main\resources\native\windows-x64\amdl.dll'
    if (-not (Test-Path -LiteralPath $nativeLibrary -PathType Leaf)) {
        throw 'The Windows download engine is missing. Run scripts/build-native.ps1 first.'
    }
    $miuixSourceRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\miuix'))
    $hadGitDir = Test-Path Env:GIT_DIR
    $hadGitWorkTree = Test-Path Env:GIT_WORK_TREE
    $previousGitDir = $env:GIT_DIR
    $previousGitWorkTree = $env:GIT_WORK_TREE
    try {
        # Miuix derives its snapshot version from Git while Gradle is configured.
        $env:GIT_DIR = Join-Path $miuixSourceRoot '.git'
        $env:GIT_WORK_TREE = $miuixSourceRoot
        $gradleArguments = @('test', 'createDistributable', '--console=plain')
        if ($RenderUi) { $gradleArguments += '-Damdl.ui.render=true' }
        & .\gradlew.bat @gradleArguments
        $gradleExitCode = $LASTEXITCODE
    } finally {
        if ($hadGitDir) { $env:GIT_DIR = $previousGitDir } else { Remove-Item Env:GIT_DIR -ErrorAction SilentlyContinue }
        if ($hadGitWorkTree) { $env:GIT_WORK_TREE = $previousGitWorkTree } else { Remove-Item Env:GIT_WORK_TREE -ErrorAction SilentlyContinue }
    }
    if ($gradleExitCode -ne 0) { throw 'Desktop build or tests failed.' }

    $appImage = Join-Path $projectRoot 'build\compose\binaries\windows\main\app\AMDL'
    if (-not (Test-Path -LiteralPath (Join-Path $appImage 'AMDL.exe'))) {
        throw 'AMDL.exe was not produced.'
    }
    $distRoot = Join-Path $projectRoot 'dist'
    $destination = Join-Path $distRoot "AMDL-$distributionVersion"
    $workspacePrefix = [System.IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    $absoluteDestination = [System.IO.Path]::GetFullPath($destination)
    if (-not $absoluteDestination.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Distribution destination is outside the project.'
    }
    New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
    if (Test-Path -LiteralPath $absoluteDestination) {
        Remove-Item -LiteralPath $absoluteDestination -Recurse -Force
    }
    Copy-Item -LiteralPath $appImage -Destination $absoluteDestination -Recurse
    Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md'), (Join-Path $projectRoot 'LICENSE'), (Join-Path $projectRoot 'BUILD-VERIFICATION.md') -Destination $absoluteDestination
    $archive = Join-Path $distRoot "AMDL-Windows-x64-$distributionVersion.zip"
    Compress-Archive -LiteralPath $absoluteDestination -DestinationPath $archive -Force
    Write-Output "Application: $absoluteDestination\AMDL.exe"
    Write-Output "Archive: $archive"
} finally { Pop-Location }
