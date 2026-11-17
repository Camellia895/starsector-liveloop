# shot.ps1 — 抓取游戏窗口帧（PrintWindow，窗口在屏外也有效）
# 用法： powershell -NoProfile -File shot.ps1 [-Out path.png]
param([string]$Out = 'C:\game\StarSector.v0.9.8a-RC8\_work\explore\liveloop\shot.png')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class SH {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  public struct R { public int L, T, Rt, B; }
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(P cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, System.Text.StringBuilder s, int m);
  public static IntPtr FindGame() {
    IntPtr f = IntPtr.Zero;
    SH.EnumWindows(delegate(IntPtr h, IntPtr l) {
      System.Text.StringBuilder c = new System.Text.StringBuilder(256);
      SH.GetClassNameW(h, c, 256);
      if (c.ToString() == "LWJGL" && SH.IsWindowVisible(h)) { f = h; return false; }
      return true;
    }, IntPtr.Zero);
    return f;
  }
}
"@
$h = [SH]::FindGame()
if ($h -eq [IntPtr]::Zero) { Write-Output 'NO-GAME-WINDOW'; exit 4 }
$r = New-Object SH+R
[void][SH]::GetWindowRect($h, [ref]$r)
$w = $r.Rt - $r.L; $hh = $r.B - $r.T
$bmp = New-Object System.Drawing.Bitmap $w, $hh
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
[void][SH]::PrintWindow($h, $dc, 2)   # PW_RENDERFULLCONTENT
$g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save($Out)
$bmp.Dispose()
Write-Output ("SHOT-OK $w" + "x" + "$hh -> $Out")
