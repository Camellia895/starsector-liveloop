# liveloop_auto.ps1 — 自动启动 LiveLoop 驾驶舱（参考 smoke_auto.ps1 的监视框架）
# CombatMain 直启（浅烟测路线）：无启动器、无点击。到主菜单 + agent API 就绪即 PASS 并保持游戏运行。
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File liveloop_auto.ps1 [-TimeoutSec 300] [-KillOnExit]
[CmdletBinding()]
param(
  [string]$Root = 'C:\game\StarSector.v0.9.8a-RC8',
  [int]$TimeoutSec = 420,
  [switch]$KillOnExit
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Win32 窗口辅助（与 smoke_auto 同源）：启动器窗口合成点击
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class W32L {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder sb, int m);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder sb, int m);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern IntPtr PostMessageW(IntPtr h, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  public static List<IntPtr> ListHandles() {
    List<IntPtr> r = new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) { r.Add(h); return true; }, IntPtr.Zero);
    return r;
  }
  public static void ParkOffScreen(IntPtr h) {
    SetWindowPos(h, (IntPtr)(-1), -32000, -32000, 0, 0, 0x1 | 0x10);
  }
  public static void MsgClick(IntPtr h, int clientX, int clientY) {
    IntPtr l = (IntPtr)(((clientY & 0xFFFF) << 16) | (clientX & 0xFFFF));
    PostMessageW(h, 0x0200, IntPtr.Zero, l);
    System.Threading.Thread.Sleep(80);
    PostMessageW(h, 0x0201, (IntPtr)1, l);
    System.Threading.Thread.Sleep(60);
    PostMessageW(h, 0x0202, IntPtr.Zero, l);
  }
}
"@

function Get-LauncherWindow {
  foreach ($h in [W32L]::ListHandles()) {
    if (-not [W32L]::IsWindowVisible($h)) { continue }
    $sb = New-Object System.Text.StringBuilder 512
    [void][W32L]::GetWindowTextW($h, $sb, 512)
    $cb = New-Object System.Text.StringBuilder 256
    [void][W32L]::GetClassNameW($h, $cb, 256)
    $p = 0
    [void][W32L]::GetWindowThreadProcessId($h, [ref]$p)
    if ([int]$p -eq $javaPid -and $cb.ToString() -eq 'LWJGL' -and $sb.ToString().Length -gt 0) {
      return [pscustomobject]@{ Hwnd = $h; Title = $sb.ToString() }
    }
  }
  return $null
}

$core   = Join-Path $Root 'starsector-core'
$logPath = Join-Path $core 'starsector.log'
$javaExe = Join-Path $Root 'jdk-28+13\bin\java.exe'
$argsFile = Join-Path $Root '_work\explore\liveloop\Miko_LiveLoop.txt'
$apiPort = 17999

if (-not (Test-Path -LiteralPath $javaExe))  { Write-Output 'LIVELOOP-ENV-FAIL: 缺少 jdk-28+13'; exit 2 }
if (-not (Test-Path -LiteralPath $argsFile)) { Write-Output 'LIVELOOP-ENV-FAIL: 缺少 Miko_LiveLoop.txt'; exit 2 }

Write-Output ("== LiveLoop 自动启动开始 ==  root=$Root  timeout=${TimeoutSec}s")

$javaBefore = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue | ForEach-Object Id)
# 游戏每次启动会截断 starsector.log → 先轮转再从 0 读
if (Test-Path -LiteralPath $logPath) {
  Move-Item -LiteralPath $logPath -Destination ($logPath + '.pre_liveloop') -Force
  $offset = 0
} else { $offset = 0 }

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$bat = Join-Path $Root 'Miko_LiveLoop.bat'
# .next 晋级：构建脚本在游戏运行时写 .next 暂存（正式 jar 被锁）；此刻游戏未启动，安全替换
foreach ($n in @(
  (Join-Path $Root '_work\explore\liveloop\agent\hotswap-agent.jar'),
  (Join-Path $Root 'mods\SimArena\jars\SimArena.jar'))) {
  if (Test-Path -LiteralPath ($n + '.next')) {
    Move-Item -LiteralPath ($n + '.next') -Destination $n -Force
    Write-Output ("  已晋级构建产物: {0}" -f $n)
  }
}
$procObj = Start-Process -FilePath $bat -WorkingDirectory $Root -PassThru
Write-Output ("  已启动 Miko_LiveLoop.bat（PID {0}），等待游戏 java 进程…" -f $procObj.Id)

$javaPid = 0
for ($i = 0; $i -lt 90; $i++) {
  Start-Sleep -Milliseconds 500
  $found = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'starfarer|liveloop' -and $javaBefore -notcontains $_.ProcessId } |
    Sort-Object CreationDate | Select-Object -Last 1
  if ($found) { $javaPid = [int]$found.ProcessId; break }
}
if ($javaPid -eq 0) { Write-Output 'LIVELOOP-FAIL: 启动后 45s 内未发现游戏 java 进程'; exit 4 }
Write-Output ("  游戏 java 进程 PID {0}" -f $javaPid)
$recent = New-Object System.Text.StringBuilder
$scanPos = 0
$readyRx  = 'Playing music with id \[miscallenous_main_menu\.ogg\]'

# 伪造激活（drive.ps1 activate 同源）：游戏无前台焦点会整体冻结，
# 用 WA_ACTIVE + WM_SETFOCUS 欺骗它，不抢真实焦点
function Spoof-Activate([IntPtr]$hwnd) {
  [void][W32L]::PostMessageW($hwnd, 0x0200, [IntPtr]0, [IntPtr]0x00050005)  # WM_MOUSEMOVE 光标停(5,5)
  [void][W32L]::PostMessageW($hwnd, 0x0086, [IntPtr]1, [IntPtr]0)          # WM_NCACTIVATE
  [void][W32L]::PostMessageW($hwnd, 0x001C, [IntPtr]1, [IntPtr]0)          # WM_ACTIVATEAPP
  [void][W32L]::PostMessageW($hwnd, 0x0006, [IntPtr]1, [IntPtr]0)          # WM_ACTIVATE WA_ACTIVE
  [void][W32L]::PostMessageW($hwnd, 0x0007, [IntPtr]0, [IntPtr]0)          # WM_SETFOCUS
}
function Get-GameWindowHwnd {
  foreach ($h in [W32L]::ListHandles()) {
    if (-not [W32L]::IsWindowVisible($h)) { continue }
    $sb = New-Object System.Text.StringBuilder 512
    [void][W32L]::GetWindowTextW($h, $sb, 512)
    $cb = New-Object System.Text.StringBuilder 256
    [void][W32L]::GetClassNameW($h, $cb, 256)
    $p = 0
    [void][W32L]::GetWindowThreadProcessId($h, [ref]$p)
    if ([int]$p -eq $javaPid -and $cb.ToString() -eq 'LWJGL' -and $sb.ToString().StartsWith('Starsector')) {
      return $h
    }
  }
  return [IntPtr]::Zero
}
$fatalRx = @(
  @{ rx = 'Exception in thread "main"';                                           why = 'main 线程未捕获异常' },
  @{ rx = '(?m)^\d+ \[[^\]]+\]\s+ERROR\s+com\.fs\.starfarer\.combat\.CombatMain'; why = 'CombatMain 致命错误' },
  @{ rx = 'java\.lang\.VerifyError';                                              why = '字节码校验失败' },
  @{ rx = 'java\.lang\.ClassFormatError';                                         why = '字节码格式非法' },
  @{ rx = 'java\.lang\.IllegalAccessError';                                       why = '嵌套类/私有访问非法' },
  @{ rx = 'java\.lang\.NoSuchMethodError';                                        why = '方法缺失（类版本错配）' },
  @{ rx = 'SecurityException';                                                    why = '脚本沙箱违规' },
  @{ rx = 'ExceptionInInitializerError';                                          why = '静态初始化失败' }
)
$menuSeen = $false; $menuAt = 0.0
$cmdsSeen = $false; $cmdsAt = 0.0
$clickDone = $false; $clickAttempts = 0; $lastClickAt = 0.0
$gameStarted = $false
$gameStartedRx = 'Running with the following mods|Running vanilla game with no mods'
$verdict = $null; $verdictWhy = ''
$fatalLines = New-Object System.Collections.Generic.List[string]

while ($true) {
  Start-Sleep -Milliseconds 800
  $t = $sw.Elapsed.TotalSeconds

  if (-not (Get-Process -Id $javaPid -ErrorAction SilentlyContinue)) {
    $verdict = 'CRASH'; $verdictWhy = ("游戏进程在 {0:N0}s 退出" -f $t); break
  }

  try {
    $len = (Get-Item -LiteralPath $logPath -ErrorAction SilentlyContinue).Length
    if ($len -gt $offset) {
      $fs = [System.IO.File]::Open($logPath, 'Open', 'Read', 'ReadWrite')
      try {
        [void]$fs.Seek($offset, 'Begin')
        $buf = New-Object byte[] ($len - $offset)
        [void]$fs.Read($buf, 0, $buf.Length)
        $offset += $buf.Length
      } finally { $fs.Close() }
      [void]$recent.Append([Text.Encoding]::GetEncoding('GBK').GetString($buf))
    }
  } catch {}
  $text = $recent.ToString()
  $newPart = if ($text.Length -gt $scanPos) { $text.Substring($scanPos) } else { '' }
  $scanPos = $text.Length

  foreach ($rule in $fatalRx) {
    foreach ($line in ($newPart -split "`n")) {
      if ($line -match $rule.rx -and $fatalLines.Count -lt 8) {
        $trimmed = $line.Trim()
        $fatalLines.Add(($rule.why + ' :: ' + $trimmed).Substring(0, [Math]::Min(220, $rule.why.Length + 4 + $trimmed.Length)))
      }
    }
  }
  if ($fatalLines.Count -gt 0) { $verdict = 'FAIL'; $verdictWhy = '日志出现致命错误'; break }

  if (-not $menuSeen -and $text -match $readyRx) {
    $menuSeen = $true; $menuAt = $t
    Write-Output ("  {0:N0}s 到达主菜单（音乐标记）" -f $t)
  }

  # 启动器窗口出现 → 合成点击"启动游戏"（50.1% x, 65.8% y，与浅烟测同坐标）
  $mainWin = Get-LauncherWindow
  if ($mainWin -and -not $clickDone) {
    $r = New-Object W32L+RECT
    [void][W32L]::GetWindowRect($mainWin.Hwnd, [ref]$r)
    $w = $r.R - $r.L; $h2 = $r.B - $r.T
    if ($w -ge 400 -and $h2 -ge 300) {
      Start-Sleep -Seconds 2
      $x = [int](0.501 * $w); $y = [int](0.658 * $h2)
      [void][W32L]::ParkOffScreen($mainWin.Hwnd)
      Start-Sleep -Milliseconds 300
      [W32L]::MsgClick($mainWin.Hwnd, $x, $y)
      $clickDone = $true; $clickAttempts++; $lastClickAt = $t
      Write-Output ("  {0:N0}s 已合成点击启动按钮（client {1}x{2}）" -f $t, $x, $y)
    }
  }
  if ($clickDone -and -not $gameStarted -and ($text -match $gameStartedRx)) {
    $gameStarted = $true
    Write-Output ("  {0:N0}s 点击生效，游戏开始加载（启动器窗口可能仍在后台）" -f $t)
  }
  if ($clickDone -and -not $gameStarted -and $mainWin -and ($t - $lastClickAt) -ge 20) {
    if ($clickAttempts -ge 3) { $verdict = 'FAIL'; $verdictWhy = '点击启动游戏 3 次均未生效'; break }
    $r = New-Object W32L+RECT
    [void][W32L]::GetWindowRect($mainWin.Hwnd, [ref]$r)
    $x = [int](0.501 * ($r.R - $r.L)); $y = [int](0.658 * ($r.B - $r.T))
    [W32L]::MsgClick($mainWin.Hwnd, $x, $y)
    $clickAttempts++; $lastClickAt = $t
    Write-Output ("  {0:N0}s 重试合成点击 #{1}" -f $t, $clickAttempts)
  }

  # agent API 检测：命令可用 + 状态机到达标题，即判定就绪（不依赖音乐标记）
  if (-not $cmdsSeen -and $t -ge 60) {
    try {
      $s = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/api/status" -f $apiPort) -TimeoutSec 3
      if ($s.commandsAvailable) {
        $st = $s.game.currentState
        Write-Output ("  {0:N0}s agent 命令可用，当前状态: {1}" -f $t, $st)
        if ($st -eq 'Title Screen State') {
          $cmdsSeen = $true; $cmdsAt = $t
        }
      }
    } catch {}
  }

  # 游戏窗口出现后周期性伪造激活，防止失焦冻结
  $ghwnd = Get-GameWindowHwnd
  if ($ghwnd -ne [IntPtr]::Zero) {
    Spoof-Activate $ghwnd
  }

  if ($cmdsSeen -and ($t - $cmdsAt) -ge 3) {
    $verdict = 'PASS'; $verdictWhy = '标题界面 + agent API + SimArena 命令全部就绪（游戏保持运行）'
    break
  }
  if ($t -ge $TimeoutSec) { $verdict = 'TIMEOUT'; $verdictWhy = "${TimeoutSec}s 内未就绪"; break }
}

if ($verdict -eq 'PASS') {
  Write-Output '  进入保活循环：每 5s 伪造激活防冻结（游戏被手动关闭时本脚本结束）'
  while (Get-Process -Id $javaPid -ErrorAction SilentlyContinue) {
    Start-Sleep -Seconds 5
    try {
      $gh = Get-GameWindowHwnd
      if ($gh -ne [IntPtr]::Zero) { Spoof-Activate $gh }
    } catch {}
  }
  Write-Output '  游戏进程已退出，保活循环结束'
}

if ($verdict -ne 'PASS' -or $KillOnExit) {
  if ($verdict -ne 'PASS') {
    # 抓线程栈作为现场证据
    $jstack = Join-Path $Root 'jdk-28+13\bin\jstack.exe'
    if (Test-Path -LiteralPath $jstack) {
      & $jstack $javaPid 2>&1 | Out-File -FilePath (Join-Path $Root '_work\explore\liveloop\threads.txt') -Encoding utf8
      Write-Output '  线程栈已保存: _work\explore\liveloop\threads.txt'
    }
  }
  taskkill /PID $javaPid /T /F 2>&1 | Out-Null
  Write-Output '  游戏进程已终止'
} else {
  Write-Output '  游戏保持运行（LiveLoop 会话）'
}
$sw.Stop()

Write-Output ''
if ($fatalLines.Count -gt 0) {
  Write-Output '--- 捕获的致命错误行（证据） ---'
  foreach ($l in $fatalLines) { Write-Output ('  ' + $l) }
  Write-Output '---'
}
if ($verdict -ne 'PASS' -and $recent.Length -gt 0) {
  $tail = $recent.ToString()
  $tail = $tail.Substring([Math]::Max(0, $tail.Length - 2400))
  Write-Output '--- 新增日志尾部（GBK 解码） ---'
  Write-Output $tail
  Write-Output '---'
}
Write-Output ''
Write-Output ("== LiveLoop 启动结果: {0} ==  原因: {1}  用时 {2:N0}s" -f $verdict, $verdictWhy, $sw.Elapsed.TotalSeconds)
if ($verdict -eq 'PASS') { exit 0 }
if ($verdict -eq 'TIMEOUT') { exit 3 }
if ($verdict -eq 'CRASH') { exit 4 }
exit 1
