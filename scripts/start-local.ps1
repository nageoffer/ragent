# =============================================
# Ragent AI - 本地一键启动脚本
# Windows PowerShell
# =============================================

$ErrorActionPreference = "Stop"
$ProjectRoot = (Get-Item $PSScriptRoot).Parent.FullName
Set-Location $ProjectRoot

function Info  { Write-Host "[INFO]  $args" -ForegroundColor Green }
function Warn  { Write-Host "[WARN]  $args" -ForegroundColor Yellow }
function Error { Write-Host "[ERROR] $args" -ForegroundColor Red; exit 1 }

# ---- Step 1: Check prerequisites ----
Info "检查运行环境..."

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Error "请先安装 Docker: https://docs.docker.com/get-docker/"
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Error "请先安装 JDK 17+: https://adoptium.net"
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Error "请先安装 Node.js 18+: https://nodejs.org"
}

Info "环境检查通过"

# ---- Step 2: Start Docker services ----
Info "启动基础设施..."
docker compose -f docker-compose.local.yml up -d

Info "等待 PostgreSQL 就绪..."
do {
    Start-Sleep 2
    $ready = docker exec ragent-postgres pg_isready -U postgres -d ragent 2>$null
} until ($LASTEXITCODE -eq 0)
Info "PostgreSQL 已就绪"

Info "等待 Milvus 就绪..."
Start-Sleep 30

# ---- Step 3: Build backend ----
Info "编译后端..."
./mvnw.cmd clean install -DskipTests

# ---- Step 4: Start MCP Server ----
Info "启动 MCP Server (端口 9099)..."
$MCPJob = Start-Job -ScriptBlock {
    Set-Location "$using:ProjectRoot/mcp-server"
    & "$using:ProjectRoot/mvnw.cmd" spring-boot:run 2>&1
}

Start-Sleep 8

# ---- Step 5: Start main backend ----
Info "启动主应用 (端口 9090)..."
$BootstrapJob = Start-Job -ScriptBlock {
    Set-Location "$using:ProjectRoot/bootstrap"
    & "$using:ProjectRoot/mvnw.cmd" spring-boot:run 2>&1
}

Start-Sleep 25

# ---- Step 6: Start frontend ----
Info "安装前端依赖..."
Set-Location "$ProjectRoot/frontend"
npm install --silent

Info "启动前端 (端口 5173)..."
$FrontendJob = Start-Job -ScriptBlock {
    Set-Location "$using:ProjectRoot/frontend"
    npm run dev 2>&1
}

Start-Sleep 6

# ---- Done ----
Set-Location $ProjectRoot
Write-Host ""
Write-Host "========================================"
Write-Host "  Ragent AI 已启动"
Write-Host "========================================"
Write-Host "  前端:      http://localhost:5173"
Write-Host "  后端 API:  http://localhost:9090/api/ragent"
Write-Host "  MCP:       http://localhost:9099"
Write-Host "  登录账号:  admin / admin"
Write-Host "========================================"
Write-Host ""
Info "停止服务: Stop-Job -Id $($MCPJob.Id), $($BootstrapJob.Id), $($FrontendJob.Id)"

Wait-Job
