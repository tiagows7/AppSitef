Add-Type -AssemblyName System.Drawing

$src = Join-Path $PSScriptRoot "..\app\src\main\res\drawable\logo_modulo_info.png"
$resRoot = Join-Path $PSScriptRoot "..\app\src\main\res"

$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

$logo = [System.Drawing.Image]::FromFile($src)
try {
    foreach ($entry in $sizes.GetEnumerator()) {
        $folder = $entry.Key
        $size = $entry.Value
        $bmp = New-Object System.Drawing.Bitmap $size, $size
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.Clear([System.Drawing.Color]::White)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $padding = [int]($size * 0.1)
        $avail = $size - (2 * $padding)
        $ratio = [Math]::Min($avail / $logo.Width, $avail / $logo.Height)
        $w = [int]($logo.Width * $ratio)
        $h = [int]($logo.Height * $ratio)
        $x = [int](($size - $w) / 2)
        $y = [int](($size - $h) / 2)
        $g.DrawImage($logo, $x, $y, $w, $h)
        $outDir = Join-Path $resRoot $folder
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        $bmp.Save((Join-Path $outDir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Save((Join-Path $outDir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose()
        $bmp.Dispose()
    }
} finally {
    $logo.Dispose()
}

Write-Output "Launcher mipmaps generated"
