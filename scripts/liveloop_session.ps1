# liveloop_session.ps1 — 会话级包装：启动失败（崩溃/超时）自动重试，最多 MaxAttempts 次
# liveloop_auto.ps1 退出码：0=PASS（子进程进入保活，游戏运行中） 3=TIMEOUT 4=CRASH 1=FAIL
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File liveloop_session.ps1 [-MaxAttempts 3] [-TimeoutSec 900]
param(
  [int]$MaxAttempts = 3,
  [int]$TimeoutSec = 900
)
for ($i = 1; $i -le $MaxAttempts; $i++) {
  Write-Output ("=== LiveLoop 尝试 {0}/{1} ===" -f $i, $MaxAttempts)
  & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'liveloop_auto.ps1') -TimeoutSec $TimeoutSec
  $code = $LASTEXITCODE
  if ($code -eq 0) {
    Write-Output 'SESSION-READY（子脚本在保活循环中；游戏关闭后本脚本结束）'
    break
  }
  if ($code -eq 4 -or $code -eq 3) {
    Write-Output ("尝试 {0} 失败（退出码 {1}），自动重试…" -f $i, $code)
    Start-Sleep -Seconds 3
    continue
  }
  Write-Output ("SESSION-FAIL：退出码 {0}，不重试" -f $code)
  break
}
