$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$projectRoot = Split-Path -Parent $PSScriptRoot
$assetDirectory = Join-Path $projectRoot 'assets'
$resourceDirectory = Join-Path $projectRoot 'src\main\resources'
New-Item -ItemType Directory -Force -Path $assetDirectory, $resourceDirectory | Out-Null

# ImageGen-prepared production master of the user-approved music-only icon.
# Packaging only crops transparent canvas and resamples the same artwork.
$masterPath = Join-Path $assetDirectory 'amdl-master.png'
if (-not (Test-Path -LiteralPath $masterPath -PathType Leaf)) {
    throw 'Missing approved icon master: assets/amdl-master.png.'
}
$master = [System.Drawing.Bitmap]::new($masterPath)
if ($master.Width -ne $master.Height -or $master.Width -lt 512) {
    $master.Dispose()
    throw 'The icon master must be square and at least 512 pixels.'
}

function ConvertTo-AmdlIcon {
    param(
        [Parameter(Mandatory = $true)][System.Drawing.Bitmap]$Source,
        [Parameter(Mandatory = $true)][System.Drawing.RectangleF]$SourceBounds,
        [Parameter(Mandatory = $true)][int]$Size
    )

    $result = [System.Drawing.Bitmap]::new($Size, $Size)
    $output = [System.Drawing.Graphics]::FromImage($result)
    try {
        $output.Clear([System.Drawing.Color]::Transparent)
        $output.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $output.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $output.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $margin = [single]($Size / 32.0)
        $destination = [System.Drawing.RectangleF]::new($margin, $margin, $Size - 2 * $margin, $Size - 2 * $margin)
        $output.DrawImage($Source, $destination, $SourceBounds, [System.Drawing.GraphicsUnit]::Pixel)
        return ,$result
    } catch {
        $result.Dispose()
        throw
    } finally { $output.Dispose() }
}

try {
    $minimumX = $master.Width
    $minimumY = $master.Height
    $maximumX = -1
    $maximumY = -1
    for ($y = 0; $y -lt $master.Height; $y++) {
        for ($x = 0; $x -lt $master.Width; $x++) {
            # Ignore near-zero alpha when measuring optical bounds.
            if ($master.GetPixel($x, $y).A -ge 128) {
                $minimumX = [Math]::Min($minimumX, $x)
                $minimumY = [Math]::Min($minimumY, $y)
                $maximumX = [Math]::Max($maximumX, $x)
                $maximumY = [Math]::Max($maximumY, $y)
            }
        }
    }
    if ($maximumX -lt 0) { throw 'The icon master has no visible artwork.' }
    $side = [single]([Math]::Max($maximumX - $minimumX + 1, $maximumY - $minimumY + 1) + 2)
    $sourceBounds = [System.Drawing.RectangleF]::new(
        [single](($minimumX + $maximumX + 1 - $side) / 2),
        [single](($minimumY + $maximumY + 1 - $side) / 2),
        $side,
        $side
    )
    $preview = ConvertTo-AmdlIcon -Source $master -SourceBounds $sourceBounds -Size 1024
    try { $preview.Save((Join-Path $assetDirectory 'amdl.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    finally { $preview.Dispose() }

    # Separate frames avoid scaling a single 256px icon at titlebar/taskbar sizes.
    $sizes = @(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
    $frames = [System.Collections.Generic.List[byte[]]]::new()
    foreach ($size in $sizes) {
        $bitmap = ConvertTo-AmdlIcon -Source $master -SourceBounds $sourceBounds -Size $size
        $png = [System.IO.MemoryStream]::new()
        try {
            $bitmap.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
            $bytes = $png.ToArray()
            $frames.Add($bytes)
            if ($size -eq 256) { [System.IO.File]::WriteAllBytes((Join-Path $resourceDirectory 'amdl.png'), $bytes) }
        } finally { $png.Dispose(); $bitmap.Dispose() }
    }

    $stream = [System.IO.File]::Create((Join-Path $assetDirectory 'amdl.ico'))
    $writer = [System.IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([uint16]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]$sizes.Count)
        $offset = 6 + 16 * $sizes.Count
        for ($index = 0; $index -lt $sizes.Count; $index++) {
            $dimension = if ($sizes[$index] -eq 256) { 0 } else { $sizes[$index] }
            $writer.Write([byte]$dimension)
            $writer.Write([byte]$dimension)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([uint16]1)
            $writer.Write([uint16]32)
            $writer.Write([uint32]$frames[$index].Length)
            $writer.Write([uint32]$offset)
            $offset += $frames[$index].Length
        }
        foreach ($frame in $frames) { $writer.Write([byte[]]$frame) }
    } finally { $writer.Dispose(); $stream.Dispose() }
    Write-Output 'Packaged the approved music icon as application PNG and 10-size Windows ICO.'
} finally { $master.Dispose() }
