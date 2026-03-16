#!/usr/bin/env bash
#
# 启停脚本：按依赖顺序启动 / 停止各服务
# 使用示例：
#   ./services.sh start all
#   ./services.sh stop all
#   ./services.sh restart all
#   ./services.sh start market
#   ./services.sh stop exchange-api

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${PROJECT_ROOT}/logs"
mkdir -p "${LOG_DIR}"

# 服务列表：name module port
# 按“依赖顺序”排列：先 Eureka，再 API，再撮合/行情/中继，再清算/结算/资金/钱包
SERVICES=(
  "cloud          cloud           7421"
  "ucenter-api    ucenter-api     6001"
  "exchange       exchange        6002"
  "exchange-api   exchange-api    6003"
  "market         market          6004"
  "exchange-relay exchange-relay  6013"
  "clearing       clearing        6005"
  "settlement     settlement      6006"
  "fund           fund            6007"
  "wallet-core    wallet/wallet-core     6009"
  "wallet-eth     wallet/eth      7003"
  "wallet-eusdt   wallet/erc-eusdt 7004"
)

usage() {
  cat <<EOF
用法:
  $0 start all              # 按依赖顺序启动所有服务
  $0 stop all               # 按逆序停止所有服务
  $0 restart all            # 停止并重新启动所有服务

  $0 start <serviceName>    # 启动单个服务
  $0 stop <serviceName>     # 停止单个服务
  $0 restart <serviceName>  # 重启单个服务

可用 serviceName:
$(printf '  - %s\n' $(for s in "${SERVICES[@]}"; do set -- $s; echo "$1"; done) | sort -u)

说明:
- 启动前会按端口检查进程，若已存在则先 kill 再启动。
- 启动命令: mvn -pl <module> spring-boot:run -DskipTests (后台运行，日志在 logs/<service>.log)
EOF
}

# 根据服务名查找一行配置
find_service() {
  local name="$1"
  for line in "${SERVICES[@]}"; do
    set -- $line
    if [[ "$1" == "$name" ]]; then
      echo "$line"
      return 0
    fi
  done
  return 1
}

# 根据端口获取 PID（可能有多个，用空格返回）
pids_by_port() {
  local port="$1"
  lsof -ti tcp:"${port}" 2>/dev/null || true
}

kill_by_port() {
  local port="$1"
  local pids
  pids="$(pids_by_port "${port}")"
  if [[ -n "$pids" ]]; then
    echo "端口 ${port} 已被占用，PID: ${pids}，先杀进程..."
    kill $pids 2>/dev/null || true
    sleep 2
    pids="$(pids_by_port "${port}")"
    if [[ -n "$pids" ]]; then
      echo "进程仍存在，执行 kill -9: ${pids}"
      kill -9 $pids 2>/dev/null || true
    fi
  fi
}

start_service() {
  local name="$1"
  local line
  line="$(find_service "$name")" || {
    echo "未知服务: ${name}"
    exit 1
  }
  set -- $line
  local svc_name="$1"
  local module="$2"
  local port="$3"

  echo "==== 启动服务: ${svc_name} (module=${module}, port=${port}) ===="

  kill_by_port "${port}"

  cd "${PROJECT_ROOT}"
  local log_file="${LOG_DIR}/${svc_name}.log"
  echo "启动命令: mvn -pl ${module} spring-boot:run -DskipTests (日志: ${log_file})"
  nohup mvn -pl "${module}" spring-boot:run -DskipTests >"${log_file}" 2>&1 &
  local pid=$!
  echo "已后台启动 ${svc_name}, PID=${pid}"
}

stop_service() {
  local name="$1"
  local line
  line="$(find_service "$name")" || {
    echo "未知服务: ${name}"
    exit 1
  }
  set -- $line
  local svc_name="$1"
  local port="$3"

  echo "==== 停止服务: ${svc_name} (port=${port}) ===="
  kill_by_port "${port}"
}

start_all() {
  echo "按依赖顺序启动所有服务..."
  # 【改动】start all 前先确保 cloud 已启动：未启动则先单独启动，已启动则跳过。
  # 【目的】如果 cloud 没在 7421 端口监听，优先拉起注册中心；已运行时不重复 kill/重启，避免影响已注册的其他服务。
  local cloud_line
  cloud_line="$(find_service "cloud")" || true
  if [[ -n "$cloud_line" ]]; then
    set -- $cloud_line
    local cloud_port="$3"
    local cloud_pids
    cloud_pids="$(pids_by_port "${cloud_port}")"
    if [[ -z "$cloud_pids" ]]; then
      echo "cloud 未检测到运行实例，先启动 cloud..."
      start_service "cloud"
      sleep 3
    else
      echo "检测到 cloud 已在端口 ${cloud_port} 运行，start all 时跳过重启 cloud。"
    fi
  fi

  for line in "${SERVICES[@]}"; do
    set -- $line
    if [[ "$1" == "cloud" ]]; then
      # cloud 已在上面处理，这里跳过，避免 kill 并重启
      continue
    fi
    start_service "$1"
    sleep 3
  done
}

stop_all() {
  echo "按逆序停止所有服务..."
  for (( idx=${#SERVICES[@]}-1 ; idx>=0 ; idx-- )); do
    line="${SERVICES[$idx]}"
    set -- $line
    # 【改动】stop all 时不杀 cloud。
    # 【目的】保留注册中心 cloud 持续运行，避免频繁 stop all 时把 Eureka 一起干掉。
    if [[ "$1" == "cloud" ]]; then
      echo "跳过停止 cloud（注册中心保持运行）"
      continue
    fi
    stop_service "$1"
  done
}

restart_service() {
  local name="$1"
  stop_service "$name"
  sleep 2
  start_service "$name"
}

restart_all() {
  stop_all
  sleep 3
  start_all
}

main() {
  local cmd="$1"
  local target="${2:-}"

  if [[ -z "$cmd" ]]; then
    usage
    exit 1
  fi

  case "$cmd" in
    start)
      if [[ "$target" == "all" ]]; then
        start_all
      elif [[ -n "$target" ]]; then
        start_service "$target"
      else
        usage
        exit 1
      fi
      ;;
    stop)
      if [[ "$target" == "all" ]]; then
        stop_all
      elif [[ -n "$target" ]]; then
        stop_service "$target"
      else
        usage
        exit 1
      fi
      ;;
    restart)
      if [[ "$target" == "all" ]]; then
        restart_all
      elif [[ -n "$target" ]]; then
        restart_service "$target"
      else
        usage
        exit 1
      fi
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"

