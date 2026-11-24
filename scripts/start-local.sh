#!/usr/bin/env bash
set -euo pipefail

# =============================================
# Ragent AI - 本地一键启动脚本
# macOS / Linux
# =============================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# ---- Step 1: Check prerequisites ----
info "检查运行环境..."

command -v docker  >/dev/null 2>&1 || error "请先安装 Docker: https://docs.docker.com/get-docker/"
command -v java    >/dev/null 2>&1 || error "请先安装 JDK 17+: https://adoptium.net"
command -v node    >/dev/null 2>&1 || error "请先安装 Node.js 18+: https://nodejs.org"

info "环境检查通过"

# ---- Step 2: Start Docker services ----
info "启动基础设施..."
docker compose -f docker-compose.local.yml up -d

info "等待 PostgreSQL 就绪..."
until docker exec ragent-postgres pg_isready -U postgres -d ragent 2>/dev/null; do
    sleep 2
done
info "PostgreSQL 已就绪"

# ---- Step 3: Build backend ----
info "编译后端..."
./mvnw clean install -DskipTests

# ---- Step 4: Start MCP Server ----
info "启动 MCP Server (端口 9099)..."
cd "$PROJECT_ROOT/mcp-server"
../mvnw spring-boot:run &
MCP_PID=$!
cd "$PROJECT_ROOT"

sleep 5

# ---- Step 5: Start main backend ----
info "启动主应用 (端口 9090)..."
cd "$PROJECT_ROOT/bootstrap"
../mvnw spring-boot:run &
BOOTSTRAP_PID=$!
cd "$PROJECT_ROOT"

sleep 20

# ---- Step 6: Start frontend ----
info "安装前端依赖..."
cd "$PROJECT_ROOT/frontend"
npm install --silent

info "启动前端 (端口 5173)..."
npm run dev &
FRONTEND_PID=$!
cd "$PROJECT_ROOT"

sleep 5

# ---- Done ----
echo ""
echo "========================================"
echo "  Ragent AI 已启动"
echo "========================================"
echo "  前端:      http://localhost:5173"
echo "  后端 API:  http://localhost:9090/api/ragent"
echo "  MCP:       http://localhost:9099"
echo "  登录账号:  admin / admin"
echo "========================================"
echo ""
info "如需停止所有服务，运行: kill $MCP_PID $BOOTSTRAP_PID $FRONTEND_PID"

wait
