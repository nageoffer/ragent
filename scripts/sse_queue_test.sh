#!/usr/bin/env bash
set -euo pipefail

# ==================== 颜色定义 ====================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
GRAY='\033[0;90m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ==================== 配置参数 ====================
BASE_URL="${BASE_URL:-http://localhost:9090/api/ragent}"
QUESTION="${QUESTION:-你是谁？你是ChatGPT么？}"
CONCURRENCY="${CONCURRENCY:-3}"
TOKEN="${TOKEN:-03c34afe1127412b9c7050f495966b28}"
CONVERSATION_ID="${CONVERSATION_ID:-}"
LOG_DIR="${LOG_DIR:-$(pwd)/logs}"

# ==================== 检测 Python 可用性 ====================
HAS_PYTHON=false
if command -v python3 &>/dev/null; then
  HAS_PYTHON=true
  PYTHON_CMD="python3"
elif command -v python &>/dev/null; then
  HAS_PYTHON=true
  PYTHON_CMD="python"
fi

# ==================== 工具函数 ====================
# 精确计算字符串显示宽度（自动降级）
get_display_width() {
  local str="$1"
  # 移除所有ANSI转义序列
  local clean_str=$(echo -e "$str" | sed 's/\x1b\[[0-9;]*m//g')

  if [[ "$HAS_PYTHON" == true ]]; then
    # 方案1：使用Python精确计算（支持中文等宽字符）
    $PYTHON_CMD -c "
import unicodedata
import sys
s = '''$clean_str'''
width = 0
for c in s:
    ea = unicodedata.east_asian_width(c)
    if ea in ('F', 'W'):  # Fullwidth or Wide
        width += 2
    elif ea in ('H', 'Na', 'N', 'A'):  # Halfwidth, Narrow, Neutral, Ambiguous
        width += 1
print(width, end='')
" 2>/dev/null || echo ${#clean_str}
  else
    # 方案2：纯Bash降级方案（估算中文字符）
    # 简单规则：ASCII占1，非ASCII占2
    local width=0
    local len=${#clean_str}
    for ((i=0; i<len; i++)); do
      local char="${clean_str:i:1}"
      # 检查是否是ASCII字符（0-127）
      if [[ $(printf '%d' "'$char") -lt 128 ]] 2>/dev/null; then
        ((width++))
      else
        ((width+=2))
      fi
    done
    echo $width
  fi
}

# 动态绘制配置框
draw_config_box() {
  local -a lines=("$@")
  local max_width=0
  local width

  # 计算最大宽度
  for line in "${lines[@]}"; do
    width=$(get_display_width "$line")
    if [[ $width -gt $max_width ]]; then
      max_width=$width
    fi
  done

  # 添加padding
  max_width=$((max_width + 4))

  # 确保最小宽度
  if [[ $max_width -lt 60 ]]; then
    max_width=60
  fi

  # 绘制顶部
  echo -e -n "${CYAN}╭"
  printf '─%.0s' $(seq 1 $max_width)
  echo -e "╮${RESET}"

  # 绘制标题
  local title=" Configuration "
  local title_width=$(get_display_width "$title")
  local padding=$(( (max_width - title_width) / 2 ))
  echo -e -n "${CYAN}│${RESET}"
  printf ' %.0s' $(seq 1 $padding)
  echo -e -n "${BOLD}${title}${RESET}"
  printf ' %.0s' $(seq 1 $((max_width - padding - title_width)))
  echo -e "${CYAN}│${RESET}"

  # 绘制分隔线
  echo -e -n "${CYAN}├"
  printf '─%.0s' $(seq 1 $max_width)
  echo -e "┤${RESET}"

  # 绘制内容行
  for line in "${lines[@]}"; do
    local line_width=$(get_display_width "$line")
    local spaces=$((max_width - line_width - 2))
    echo -e -n "${CYAN}│${RESET} ${line}"
    printf ' %.0s' $(seq 1 $spaces)
    echo -e " ${CYAN}│${RESET}"
  done

  # 绘制底部
  echo -e -n "${CYAN}╰"
  printf '─%.0s' $(seq 1 $max_width)
  echo -e "╯${RESET}"
}

# ==================== 炫酷的Banner ====================
clear
echo -e "${CYAN}${BOLD}"
cat << 'EOF'
 ____ ____  _____   _____
/ ___/ ___|| ____| |_   _| __ __ _  ___ ___
\___ \___ \|  _|     | || '__/ _` |/ __/ _ \
 ___) |__) | |___    | || | | (_| | (_|  __/
|____/____/|_____|   |_||_|  \__,_|\___\___|
EOF

echo -e "${RESET}"
echo -e "${MAGENTA}╔══════════════════════════════════════════════════════════════╗${RESET}"
echo -e "${MAGENTA}║${RESET}  ${BOLD}SSE Real-time Streaming Test Suite${RESET}                          ${MAGENTA}║${RESET}"
echo -e "${MAGENTA}╚══════════════════════════════════════════════════════════════╝${RESET}"

# 显示环境信息
if [[ "$HAS_PYTHON" == true ]]; then
  echo -e "${DIM}${GRAY}[✓] Using $PYTHON_CMD for precise width calculation${RESET}"
else
  echo -e "${DIM}${YELLOW}[!] Python not found, using fallback method${RESET}"
fi
echo ""

# ==================== 准备配置信息 ====================
declare -a config_lines

config_lines+=("${MAGENTA}▸${RESET} ${CYAN}Endpoint    ${RESET}: ${YELLOW}${BASE_URL}${RESET}")
config_lines+=("${MAGENTA}▸${RESET} ${CYAN}Question    ${RESET}: ${YELLOW}${QUESTION}${RESET}")
config_lines+=("${MAGENTA}▸${RESET} ${CYAN}Concurrency ${RESET}: ${YELLOW}${CONCURRENCY}${RESET}")
config_lines+=("${MAGENTA}▸${RESET} ${CYAN}Log Dir     ${RESET}: ${YELLOW}${LOG_DIR}${RESET}")

if [[ -n "${TOKEN}" ]]; then
  TOKEN_DISPLAY="${TOKEN:0:8}●●●●${TOKEN: -4}"
  config_lines+=("${MAGENTA}▸${RESET} ${CYAN}Auth Token  ${RESET}: ${YELLOW}${TOKEN_DISPLAY}${RESET} ${GREEN}✓${RESET}")
else
  config_lines+=("${MAGENTA}▸${RESET} ${CYAN}Auth Token  ${RESET}: ${RED}[Not Set]${RESET}")
fi

# 绘制配置框
draw_config_box "${config_lines[@]}"
echo ""

# ==================== 准备环境 ====================
AUTH_HEADER=()
if [[ -n "${TOKEN}" ]]; then
  AUTH_HEADER=(-H "Authorization: ${TOKEN}")
fi

mkdir -p "${LOG_DIR}"

# ==================== 启动并发请求 ====================
echo -e "${YELLOW}${BOLD}🚀 Launching ${CONCURRENCY} concurrent SSE streams...${RESET}"
echo ""

START_TIME=$(date +%s)

# ==================== 核心业务逻辑（完全不变）====================
for i in $(seq 1 "${CONCURRENCY}"); do
  (
    echo -e "${CYAN}⚡${RESET} Worker #${i} ${GRAY}[PID: $$]${RESET} spawned"

    curl -G -N --no-buffer \
      "${AUTH_HEADER[@]}" \
      --data-urlencode "question=${QUESTION}_${i}" \
      ${CONVERSATION_ID:+--data-urlencode "conversationId=${CONVERSATION_ID}"} \
      --data-urlencode "deepThinking=false" \
      "${BASE_URL}/rag/v3/chat" 2>/dev/null | \
      perl -ne 'use Time::HiRes qw(gettimeofday); use POSIX qw(strftime); chomp; my ($s,$us)=gettimeofday; my $ts=strftime("%Y-%m-%d %H:%M:%S", localtime($s)); printf "[%s.%03d] %s\n", $ts, $us/1000, $_;' \
      > "${LOG_DIR}/ragent_chat_${i}.log"

    EXIT_CODE=$?
    if [[ $EXIT_CODE -eq 0 ]]; then
      echo -e "${GREEN}✓${RESET} Worker #${i} completed successfully"
    else
      echo -e "${RED}✗${RESET} Worker #${i} failed with exit code ${EXIT_CODE}"
    fi
  ) &
  sleep 0.1
done

echo ""
echo -e "${YELLOW}⏳ Streaming in progress... waiting for completion${RESET}"
echo ""

wait

END_TIME=$(date +%s)
TOTAL_TIME=$((END_TIME - START_TIME))

# ==================== 结果展示 ====================
echo ""

declare -a summary_lines
summary_lines+=("${MAGENTA}▸${RESET} ${CYAN}Total Workers${RESET}: ${YELLOW}${CONCURRENCY}${RESET}")
summary_lines+=("${MAGENTA}▸${RESET} ${CYAN}Total Time   ${RESET}: ${YELLOW}${TOTAL_TIME}s${RESET}")
summary_lines+=("${MAGENTA}▸${RESET} ${CYAN}Log Location ${RESET}: ${YELLOW}${LOG_DIR}${RESET}")

draw_summary_box() {
  local -a lines=("$@")
  local max_width=0
  local width

  for line in "${lines[@]}"; do
    width=$(get_display_width "$line")
    if [[ $width -gt $max_width ]]; then
      max_width=$width
    fi
  done

  max_width=$((max_width + 4))
  if [[ $max_width -lt 60 ]]; then
    max_width=60
  fi

  echo -e -n "${CYAN}╭"
  printf '─%.0s' $(seq 1 $max_width)
  echo -e "╮${RESET}"

  local title=" Execution Summary "
  local title_width=$(get_display_width "$title")
  local padding=$(( (max_width - title_width) / 2 ))
  echo -e -n "${CYAN}│${RESET}"
  printf ' %.0s' $(seq 1 $padding)
  echo -e -n "${BOLD}${title}${RESET}"
  printf ' %.0s' $(seq 1 $((max_width - padding - title_width)))
  echo -e "${CYAN}│${RESET}"

  echo -e -n "${CYAN}├"
  printf '─%.0s' $(seq 1 $max_width)
  echo -e "┤${RESET}"

  for line in "${lines[@]}"; do
    local line_width=$(get_display_width "$line")
    local spaces=$((max_width - line_width - 2))
    echo -e -n "${CYAN}│${RESET} ${line}"
    printf ' %.0s' $(seq 1 $spaces)
    echo -e " ${CYAN}│${RESET}"
  done

  echo -e -n "${CYAN}╰"
  printf '─%.0s' $(seq 1 $max_width)
  echo -e "╯${RESET}"
}

draw_summary_box "${summary_lines[@]}"
echo ""

# ==================== 文件列表 ====================
echo -e "${BLUE}📁 Generated log files:${RESET}"
for i in $(seq 1 "${CONCURRENCY}"); do
  LOG_FILE="${LOG_DIR}/ragent_chat_${i}.log"
  if [[ -f "${LOG_FILE}" ]]; then
    SIZE=$(du -h "${LOG_FILE}" | cut -f1)
    LINES=$(wc -l < "${LOG_FILE}")
    echo -e "   ${GREEN}✓${RESET} ${GRAY}ragent_chat_${i}.log${RESET} ${DIM}(${SIZE}, ${LINES} lines)${RESET}"
  else
    echo -e "   ${RED}✗${RESET} ${GRAY}ragent_chat_${i}.log${RESET} ${RED}[Missing]${RESET}"
  fi
done

echo ""
echo -e "${GREEN}${BOLD}✓ All streams completed!${RESET}"
echo ""
echo -e "${GRAY}${DIM}────────────────────────────────────────${RESET}"
echo -e "${GRAY}${DIM}Finished at $(date '+%Y-%m-%d %H:%M:%S')${RESET}"
