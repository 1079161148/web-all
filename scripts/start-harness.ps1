# =====================================================================
# 启动 DeepSeek Harness（dsh）Web UI —— 供中台 /ai/harness 页面嵌入
# =====================================================================
# 用法：pnpm harness      （或 powershell -File scripts/start-harness.ps1）
#
# 做四件事：
#   1. 若 3080 已在监听则直接退出（幂等，可反复执行）
#   2. 解析模型凭据（DEEPSEEK_API_KEY 等）并**注入子进程环境**
#   3. 用 --no-open 启动 dsh，输出重定向到 .dsh/runtime.log
#      —— vite 的 dev-harness-proxy 插件从这个日志里读一次性 token，
#         自动完成会话握手（详见 apps/admin/vite.config.ts）
#   4. 等待端口就绪并打印入口
#
# <h3>凭据为什么走环境变量而不是 Web UI 里填</h3>
# dsh 自身的凭据优先级（源码 dsh-credentials-local 的说明，从高到低）：
#   1. 继承的环境变量            ← 本脚本注入的就是这一层（最高，只读）
#   2. $DSH_HOME/.credentials.yaml  ← Web UI「模型」页写入的那份
#   3. <启动目录>/.env           ← 只读兜底
#   4. $DSH_HOME/.env            ← 只读兜底
# 环境变量优先级最高，官方解释是"它代表本次运行的显式意图"
# （CI secret / 容器 -e 都是这个语义）。对团队来说它的好处很实在：
#   - key 不进 Web UI 的存储文件，换机器/重建容器只要给环境变量
#   - 中台侧一份 .env 管住所有 AI 相关凭据（与 AI_API_KEY 的用法一致）
#   - 轮换 key 只需改环境变量后重启 dsh，不用点界面
#
# <h3>可注入的环境变量（dsh 读取的）</h3>
#   DEEPSEEK_API_KEY          模型凭据（本脚本解析后注入）
#   DEEPSEEK_BASE_URL         覆盖 API 基址（指向自建/兼容网关时用）
#   DEEPSEEK_SEARCH_BASE_URL  联网搜索基址（可选）
#   DEEPSEEK_REASONING        推理档位透传（可选）
#   DSH_TELEMETRY_DISABLED    遥测开关（本脚本默认置 1，见下方注释）

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $root '.dsh'
$logFile = Join-Path $runtimeDir 'runtime.log'

# ---------------------------------------------------------------------
# 1. 模型凭据：进程环境 → 用户级环境 → 项目根 .env（后者便于随项目分发）
# ---------------------------------------------------------------------
function Resolve-Setting {
    param([string]$Name)

    if ([Environment]::GetEnvironmentVariable($Name, 'Process')) {
        return @{ Value = [Environment]::GetEnvironmentVariable($Name, 'Process'); Source = '进程环境变量' }
    }
    if ([Environment]::GetEnvironmentVariable($Name, 'User')) {
        return @{ Value = [Environment]::GetEnvironmentVariable($Name, 'User'); Source = '用户级环境变量' }
    }
    $envFile = Join-Path $root '.env'
    if (Test-Path $envFile) {
        # 逐行匹配 NAME=value（容忍引号与首尾空格）；不引入 dotenv 依赖
        $match = Select-String -Path $envFile -Pattern ("^\s*" + [regex]::Escape($Name) + "\s*=") |
            Select-Object -First 1
        if ($match) {
            $value = ($match.Line -split '=', 2)[1].Trim().Trim('"').Trim("'")
            if ($value) {
                return @{ Value = $value; Source = "项目 .env" }
            }
        }
    }
    return $null
}

$apiKey = Resolve-Setting 'DEEPSEEK_API_KEY'
if ($apiKey) {
    # 只设置当前进程的环境变量：Start-Process 启动的子进程自动继承，
    # 且 key 不会出现在任何命令行里（比 `cmd /c "set X=... && ..."` 安全）
    $env:DEEPSEEK_API_KEY = $apiKey.Value
    Write-Host "[harness] 已注入 DEEPSEEK_API_KEY（来源：$($apiKey.Source)）"
} else {
    Write-Host '[harness] ⚠ 未找到 DEEPSEEK_API_KEY —— Harness 里发消息会报 MISSING_CREDENTIAL。'
    Write-Host '          配置方式（任选其一，配置后重新执行 pnpm harness）：'
    Write-Host '            a) 用户级环境变量：setx DEEPSEEK_API_KEY "sk-..."'
    Write-Host "            b) 项目根 .env 文件：DEEPSEEK_API_KEY=sk-...（$root\.env）"
    Write-Host '            c) 启动后在 Harness「设置 → 模型」页面里填（写入 .credentials.yaml）'
}

$baseUrl = Resolve-Setting 'DEEPSEEK_BASE_URL'
if ($baseUrl) {
    $env:DEEPSEEK_BASE_URL = $baseUrl.Value
    Write-Host "[harness] 已注入 DEEPSEEK_BASE_URL（来源：$($baseUrl.Source)）"
}

# 遥测：企业内网部署默认关闭（数据不出内网）。需要上报时删除或置 0。
$env:DSH_TELEMETRY_DISABLED = '1'

# ---------------------------------------------------------------------
# 2. 启动（幂等）
# ---------------------------------------------------------------------
if (Get-NetTCPConnection -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host '[harness] 已在运行：http://127.0.0.1:3080（跳过启动）'
    Write-Host '[harness] 注意：环境变量只在启动时读取，改了凭据需先停掉旧进程再启动'
    exit 0
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
# 覆盖写：新实例会签发新 token，旧 token 已失效，日志必须只保留最新一次
if (Test-Path $logFile) { Remove-Item $logFile -Force }

Write-Host '[harness] 正在启动 dsh（首次运行需下载依赖，约 1~2 分钟）…'
Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c', "npx --yes @deepseek-ai/dsh web --no-open > `"$logFile`" 2>&1" `
    -WindowStyle Hidden

for ($i = 1; $i -le 60; $i++) {
    Start-Sleep -Seconds 3
    if (Get-NetTCPConnection -LocalPort 3080 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "[harness] 已就绪：http://127.0.0.1:3080（第 $($i * 3) 秒）"
        Write-Host '[harness] 打开中台 /ai/harness 页面即可（同源代理会自动完成认证握手）'
        exit 0
    }
}

Write-Host '[harness] 启动超时。查看日志：' $logFile
exit 1
