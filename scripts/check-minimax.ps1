<#
.SYNOPSIS
    MiniMax Key / 模型 ID 一键自查（绕过 AgentScope，直接打官方 API）。

.DESCRIPTION
    用来区分三类失败，避免每次都去翻应用日志猜：
      1) Key 无效      -> /v1/models 也 401
      2) 模型 ID 不存在 -> /v1/models 200，但 chat/completions 报 model not found
      3) 一切正常      -> chat/completions 返回 200

    不消耗对话额度以外的任何东西；chat/completions 那次会产生一次极小的调用费用。

.PARAMETER ApiKey
    MiniMax 开放平台的 API Key。不传则读环境变量 $env:MINIMAX_API_KEY。

.PARAMETER BaseUrl
    国内端点默认 https://api.minimaxi.com/v1；国际站用 https://api.minimax.io/v1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\check-minimax.ps1
    powershell -ExecutionPolicy Bypass -File scripts\check-minimax.ps1 -ApiKey 'sk-xxx'
#>
param(
    [string]$ApiKey = $env:MINIMAX_API_KEY,
    [string]$BaseUrl = 'https://api.minimaxi.com/v1'
)

$ErrorActionPreference = 'Continue'
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
if (-not $curl) { Write-Error '找不到 curl.exe（Windows 10+ 自带）'; exit 1 }

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Host '[FAIL] 没有 API Key：先设 $env:MINIMAX_API_KEY 或用 -ApiKey 传入' -ForegroundColor Red
    exit 1
}
if ($ApiKey -ne $ApiKey.Trim()) {
    Write-Host '[WARN] Key 首尾有空白字符 —— 复制时最容易出的错，继续测试' -ForegroundColor Yellow
}

Write-Host "端点    : $BaseUrl" -ForegroundColor Cyan
Write-Host "Key     : $($ApiKey.Substring(0, [Math]::Min(6, $ApiKey.Length)))...(len=$($ApiKey.Length))" -ForegroundColor Cyan
Write-Host ''

# ---------- 第 1 步：列模型（不携带模型名，用来单独判鉴权） ----------
Write-Host '=== [1/3] GET /v1/models —— 只验鉴权，与模型 ID 无关 ===' -ForegroundColor Cyan
$modelsRaw = & $curl -s -w "`n__HTTP__%{http_code}" "$BaseUrl/models" -H "Authorization: Bearer $ApiKey" 2>&1
$modelsRaw | Out-String | Write-Host
$modelsText = ($modelsRaw -join "`n")
$modelsCode = if ($modelsText -match '__HTTP__(\d{3})') { $Matches[1] } else { '000' }

if ($modelsCode -ne '200') {
    Write-Host ''
    Write-Host "[FAIL] /v1/models 返回 $modelsCode —— 这是【鉴权问题】，不是模型 ID 问题。" -ForegroundColor Red
    Write-Host '  逐条排查：' -ForegroundColor Yellow
    Write-Host '   1. Key 是否已被重置/删除（重置后旧 Key 立即失效）' -ForegroundColor Yellow
    Write-Host '   2. Key 是否来自【开放平台】platform.minimaxi.com（不是主站/网页版的 token）' -ForegroundColor Yellow
    Write-Host '   3. 账号是否已开通 API 权限 / 是否有欠费' -ForegroundColor Yellow
    Write-Host '   4. 复制时有没有多带空格或漏字符' -ForegroundColor Yellow
    Write-Host '  若是国际站账号，把 -BaseUrl 换成 https://api.minimax.io/v1' -ForegroundColor Yellow
    exit 1
}

# ---------- 第 2 步：解析可用模型 ----------
Write-Host ''
Write-Host '=== [2/3] 你账号可用的模型 ID ===' -ForegroundColor Cyan
$json = $modelsText -replace '(?s)^.*?__HTTP__\d{3}$', '' | ConvertFrom-Json
$ids = @($json.data | ForEach-Object { $_.id })
$ids | ForEach-Object { Write-Host "  - $_" -ForegroundColor Green }

$chatModels = @($ids | Where-Object { $_ -match 'MiniMax-M\d' })
if ($chatModels.Count -eq 0) {
    Write-Host '[WARN] 没找到 MiniMax-M* 系列的 chat 模型，下面挑第一个可用的试' -ForegroundColor Yellow
    $target = $ids[0]
} else {
    $target = $chatModels[0]
    Write-Host "  -> 用 $target 做实测" -ForegroundColor Cyan
}

# ---------- 第 3 步：真实调一次 ----------
Write-Host ''
Write-Host "=== [3/3] 实调一次 chat/completions（model=$target）===" -ForegroundColor Cyan
$payload = @{
    model               = $target
    messages            = @(@{ role = 'user'; content = 'hi' })
    max_completion_tokens = 32
} | ConvertTo-Json -Depth 5 -Compress

$chatRaw = & $curl -s -w "`n__HTTP__%{http_code}" -X POST "$BaseUrl/chat/completions" `
    -H "Authorization: Bearer $ApiKey" -H 'Content-Type: application/json' `
    -d $payload 2>&1
$chatText = ($chatRaw -join "`n")
$chatCode = if ($chatText -match '__HTTP__(\d{3})') { $Matches[1] } else { '000' }

if ($chatCode -eq '200') {
    Write-Host ''
    Write-Host '[OK] 全链路通：Key 有效 + 模型 ID 有效 + 端点正确' -ForegroundColor Green
    $reply = ($chatText -replace '(?s)^.*?__HTTP__\d{3}$', '') | ConvertFrom-Json
    Write-Host "回复: $($reply.choices[0].message.content)" -ForegroundColor Gray
    Write-Host ''
    Write-Host '把上面这个模型 ID 填到 application.yml 的 mvp.model.model 即可。' -ForegroundColor Cyan
} else {
    Write-Host ''
    Write-Host "[FAIL] chat/completions 返回 $chatCode" -ForegroundColor Red
    Write-Host '（鉴权已通过，若是 400/404 那就是模型 ID 不在上面的列表里，换一个）' -ForegroundColor Yellow
    Write-Host ($chatText -replace '(?s)^.*?__HTTP__\d{3}$', '') -ForegroundColor Gray
}
