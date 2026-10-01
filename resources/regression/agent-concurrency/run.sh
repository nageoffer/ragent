#!/usr/bin/env bash
set -euo pipefail
suite_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$suite_dir/../../.." && pwd)"
classes_dir="$suite_dir/artifacts/classes"
mkdir -p "$classes_dir"
javac -encoding UTF-8 -d "$classes_dir" \
  "$repo_dir"/resources/initializer/common/*.java \
  "$suite_dir"/*.java
cd -- "$repo_dir"
exec java -cp "$classes_dir" com.nageoffer.ai.ragent.initializer.ConcurrencyRegressionMain "$@"
