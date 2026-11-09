param([switch]$Diagnostics)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

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

$jdkDirectory = $jdkCandidates |
    Select-Object -Unique |
    Where-Object {
        (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe')) -and
        ((Get-JdkMajorVersion -JdkHome $_) -eq 21)
    } |
    Select-Object -First 1
if (-not $jdkDirectory) {
    throw 'A complete x64 JDK 21 is required to build and run AMDL. Set JAVA_HOME to a JDK 21 directory.'
}
$env:JAVA_HOME = $jdkDirectory
Push-Location -LiteralPath $projectRoot
try {
    $miuixSourceRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\miuix'))
    $previousGitDir = $env:GIT_DIR
    $previousGitWorkTree = $env:GIT_WORK_TREE
    $hadGitDir = Test-Path Env:GIT_DIR
    $hadGitWorkTree = Test-Path Env:GIT_WORK_TREE
    $env:GIT_DIR = Join-Path $miuixSourceRoot '.git'
    $env:GIT_WORK_TREE = $miuixSourceRoot
    if ($Diagnostics) { & .\gradlew.bat run --args='--diagnostics' --console=plain }
    else { & .\gradlew.bat run --console=plain }
    $gradleExitCode = $LASTEXITCODE
    if ($hadGitDir) { $env:GIT_DIR = $previousGitDir } else { Remove-Item Env:GIT_DIR -ErrorAction SilentlyContinue }
    if ($hadGitWorkTree) { $env:GIT_WORK_TREE = $previousGitWorkTree } else { Remove-Item Env:GIT_WORK_TREE -ErrorAction SilentlyContinue }
    if ($gradleExitCode -ne 0) { throw 'AMDL did not start successfully. Check the build output.' }
} finally { Pop-Location }
