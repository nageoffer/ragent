#!/usr/bin/env bash
set -euo pipefail
suite_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$suite_dir/../../.." && pwd)"
classes_dir="$suite_dir/artifacts/classes"

# 离线重放：生产压缩器在 agent 模块里，借 surefire 拿它的类路径跑，产物再交给下面同一套判定
replay_status=""
if [[ "${1:-}" == "--replay" ]]; then
  if [[ $# -lt 2 ]]; then
    echo "用法: run.sh --replay <实跑产物目录> [--repeat N]" >&2
    exit 2
  fi
  source_dir="$(cd -- "$2" && pwd)"
  repeat=1
  if [[ "${3:-}" == "--repeat" ]]; then
    repeat="${4:?--repeat 后面要跟次数}"
  fi
  target_dir="$suite_dir/artifacts/$(date +%Y%m%d-%H%M%S)-replay"
  replay_status=0
  (cd -- "$repo_dir" && RAGENT_COMPACTION_REPLAY_SOURCE="$source_dir" RAGENT_COMPACTION_REPLAY_TARGET="$target_dir" \
    RAGENT_COMPACTION_REPLAY_SUITE="$suite_dir" RAGENT_COMPACTION_REPLAY_REPEAT="$repeat" \
    mvn -q -pl agent -am test -Dtest=AgentCompactionReplayLiveTest -Dsurefire.failIfNoSpecifiedTests=false) \
    || replay_status=$?
  if [[ ! -f "$target_dir/facts.json" ]]; then
    echo "[compaction] 重放没有产出，见上面的 Maven 输出" >&2
    exit 1
  fi
  set -- --evaluate "$target_dir"
fi

mkdir -p "$classes_dir"
javac -encoding UTF-8 -d "$classes_dir" \
  "$repo_dir"/resources/initializer/common/*.java \
  "$suite_dir"/*.java
cd -- "$repo_dir"
if [[ "${1:-}" == "--self-test" ]]; then
  exec java -cp "$classes_dir" com.nageoffer.ai.ragent.initializer.CompactionChecksSelfTest "$suite_dir"
fi
main=(java -cp "$classes_dir" com.nageoffer.ai.ragent.initializer.CompactionRegressionMain --suite-dir "$suite_dir" "$@")
if [[ -n "$replay_status" ]]; then
  # 有会话没重放完时报告照出，但退出码不能是 0
  "${main[@]}" || exit $?
  exit "$replay_status"
fi
# 整轮约一小时，macOS 空闲睡眠会把进行中的轮次打成中断，跑完之前不许睡
if command -v caffeinate >/dev/null 2>&1; then
  exec caffeinate -i -s "${main[@]}"
fi
exec "${main[@]}"
