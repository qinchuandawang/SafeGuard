#!/bin/bash
# SafeGuard 一键部署脚本
# ============================================
# 用法：
#   bash deploy.sh              # 启动核心服务
#   bash deploy.sh full         # 启动全部服务
#   bash deploy.sh ai           # 核心 + AI 检测
#   bash deploy.sh stop         # 停止
# ============================================

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

check_docker() {
  if ! docker ps > /dev/null 2>&1; then
    log_error "Docker 引擎未运行，请先启动 Docker Desktop"
    exit 1
  fi
  log_info "Docker 就绪 ✓"
}

check_env() {
  if [ ! -f .env ]; then
    log_warn ".env 文件不存在，使用默认值（AI 功能不可用）"
    cp .env.example .env
  fi
  log_info ".env 配置文件 ✓"
}

CMD="${1:-core}"

case "$CMD" in
  core|"")
    log_info "启动核心服务（MySQL + Qdrant + Backend）..."
    check_docker
    check_env
    docker compose up -d --build
    echo ""
    log_info "服务访问地址："
    echo "  后端 API:      http://localhost:8080"
    echo "  管理后台:      http://localhost:8080/admin"
    echo "  MySQL:         localhost:3307  (root/root123)"
    echo "  Qdrant:        localhost:6333"
    echo ""
    echo "提示：前端开发请运行 cd web-admin && npm run dev"
    echo "提示：AI 检测服务请运行 bash deploy.sh ai"
    ;;

  ai)
    log_info "启动核心 + AI 检测服务（音频 + 视频）..."
    check_docker
    check_env
    docker compose --profile ai up -d --build
    echo ""
    log_info "AI 服务已启动："
    echo "  音频检测:      http://localhost:5000"
    echo "  视频检测:      http://localhost:5002"
    ;;

  web)
    log_info "启动核心 + 管理后台 SPA..."
    check_docker
    docker compose --profile web up -d --build
    echo ""
    log_info "管理后台已启动："
    echo "  SPA:           http://localhost:8085"
    ;;

  full)
    log_info "启动全部服务..."
    check_docker
    check_env
    docker compose --profile ai --profile web up -d --build
    echo ""
    log_info "全部服务已启动："
    echo "  后端 API:      http://localhost:8080"
    echo "  管理后台:      http://localhost:8080/admin (Thymeleaf)"
    echo "  SPA 前端:      http://localhost:8085 (Vue.js)"
    echo "  音频检测:      http://localhost:5000"
    echo "  视频检测:      http://localhost:5002"
    echo "  MySQL:         localhost:3307"
    echo "  Qdrant:        localhost:6333"
    ;;

  stop)
    log_info "停止所有服务..."
    docker compose --profile ai --profile web down 2>/dev/null || docker compose down
    log_info "已停止 ✓"
    ;;

  restart)
    log_info "重启所有服务..."
    docker compose --profile ai --profile web down 2>/dev/null || true
    docker compose --profile ai --profile web up -d --build
    ;;

  logs)
    docker compose logs -f "${2:-backend}"
    ;;

  status)
    docker compose ps
    ;;

  clean)
    log_warn "清除所有容器和数据卷（数据将丢失！）"
    read -p "确认继续？(y/N): " confirm
    if [ "$confirm" = "y" ]; then
      docker compose --profile ai --profile web down -v
      log_info "清除完成"
    else
      log_info "已取消"
    fi
    ;;

  *)
    echo "用法: bash deploy.sh [命令]"
    echo ""
    echo "命令："
    echo "  core     启动核心服务：MySQL + Qdrant + Backend（默认，推荐低配机器）"
    echo "  ai       核心 + AI 检测服务（音频/视频，需 8GB+ 内存）"
    echo "  web      核心 + 管理后台 SPA（Vue.js 前端）"
    echo "  full     启动全部服务"
    echo "  stop     停止所有服务"
    echo "  restart  重启所有服务"
    echo "  logs     查看日志（例：bash deploy.sh logs backend）"
    echo "  status   查看服务状态"
    echo "  clean    清除所有容器和数据卷"
    ;;
esac
