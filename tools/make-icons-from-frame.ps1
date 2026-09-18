param(
  [string]$SourceGif,
  [int]$Frame = 4,
  [string]$ProjectRoot
)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($SourceGif)
$fd = New-Object System.Drawing.Imaging.FrameDimension $src.FrameDimensionsList[0]
$src.SelectActiveFrame($fd, $Frame) | Out-Null

function New-Icon([int]$size, [string]$outPath) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::White)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $side = [Math]::Max($src.Width, $src.Height)
  $scale = $size / $side
  $w = [int]([Math]::Round($src.Width * $scale))
  $h = [int]([Math]::Round($src.Height * $scale))
  $x = [int]((($size - $w) / 2))
  $y = [int]((($size - $h) / 2))
  $g.DrawImage($src, $x, $y, $w, $h)
  $g.Dispose()
  $dir = Split-Path $outPath -Parent
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Output ("OK " + $outPath + " " + $size)
}

$A = Join-Path $ProjectRoot 'assets'
New-Icon 1024 (Join-Path $A 'icon-1024.png')
New-Icon 512 (Join-Path $A 'icon-512.png')
New-Icon 256 (Join-Path $A 'icon.png')
New-Icon 192 (Join-Path $A 'icon-192.png')
New-Icon 144 (Join-Path $A 'icon-144.png')
New-Icon 96 (Join-Path $A 'icon-96.png')
New-Icon 72 (Join-Path $A 'icon-72.png')
New-Icon 48 (Join-Path $A 'icon-48.png')
New-Icon 32 (Join-Path $A 'tray.png')

New-Icon 48 (Join-Path $ProjectRoot 'android-app\res\mipmap-mdpi\ic_launcher.png')
New-Icon 72 (Join-Path $ProjectRoot 'android-app\res\mipmap-hdpi\ic_launcher.png')
New-Icon 96 (Join-Path $ProjectRoot 'android-app\res\mipmap-xhdpi\ic_launcher.png')
New-Icon 144 (Join-Path $ProjectRoot 'android-app\res\mipmap-xxhdpi\ic_launcher.png')
New-Icon 192 (Join-Path $ProjectRoot 'android-app\res\mipmap-xxxhdpi\ic_launcher.png')

New-Icon 1024 (Join-Path $ProjectRoot 'ios-app\DataBridge\Assets.xcassets\AppIcon.appiconset\icon-1024.png')
$src.Dispose()
Write-Output 'ALL DONE'
