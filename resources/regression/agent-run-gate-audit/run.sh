#!/usr/bin/env bash
set -euo pipefail
suite_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$suite_dir/../../.." && pwd)"
exec java "$suite_dir/AgentRunGateAudit.java" "$repo_dir" "$@"
