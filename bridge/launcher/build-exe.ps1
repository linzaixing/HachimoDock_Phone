# Rebuild HachimoPhone.exe (uses the csc.exe bundled with .NET Framework 4.x)
# Usage: powershell -ExecutionPolicy Bypass -File bridge/launcher/build-exe.ps1
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 can parse it in any locale.

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$ico = Join-Path $PSScriptRoot 'hachimo.ico'
$png = Join-Path $root 'assets\logo_openpano.png'
$exe = Join-Path $root 'HachimoPhone.exe'
$cs = Join-Path $PSScriptRoot 'HachimoPhoneLauncher.cs'
$csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'

if (-not (Test-Path $csc)) { throw "csc.exe not found: $csc" }

# 256x256 PNG -> single-entry ICO (PNG-compressed ICO is supported by Windows Vista+)
if (Test-Path $png) {
    Add-Type -AssemblyName System.Drawing
    $src = [System.Drawing.Bitmap]::FromFile($png)
    $bmp = New-Object System.Drawing.Bitmap 256, 256
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($src, 0, 0, 256, 256)
    $g.Dispose()
    $src.Dispose()
    $ms = New-Object IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $data = $ms.ToArray()
    $ms.Dispose()

    $out = New-Object IO.MemoryStream
    $bw = New-Object IO.BinaryWriter($out)
    $bw.Write([UInt16]0)
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]1)
    $bw.Write([Byte]0)
    $bw.Write([Byte]0)
    $bw.Write([Byte]0)
    $bw.Write([Byte]0)
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]$data.Length)
    $bw.Write([UInt32]22)
    $bw.Write($data)
    $bw.Flush()
    [IO.File]::WriteAllBytes($ico, $out.ToArray())
    $bw.Dispose()
    $out.Dispose()
}

& $csc /nologo /target:exe /optimize+ "/out:$exe" "/win32icon:$ico" $cs
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }
Write-Host "built $exe"
