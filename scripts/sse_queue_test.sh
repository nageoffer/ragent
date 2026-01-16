#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/ragent}"
QUESTION="${QUESTION:-你是谁？你是ChatGPT么？}"
CONCURRENCY="${CONCURRENCY:-3}"
TOKEN="${TOKEN:-214630a9cef34765a6df2076aa0c4610}"
CONVERSATION_ID="${CONVERSATION_ID:-}"

echo "Base URL: ${BASE_URL}"
echo "Question: ${QUESTION}"
echo "Concurrency: ${CONCURRENCY}"
if [[ -n "${TOKEN}" ]]; then
  echo "Token: [set]"
else
  echo "Token: [empty]"
fi

AUTH_HEADER=()
if [[ -n "${TOKEN}" ]]; then
  AUTH_HEADER=(-H "Authorization: ${TOKEN}")
fi

for i in $(seq 1 "${CONCURRENCY}"); do
  (
    echo "---- request ${i} ----"
    curl -G -N --no-buffer \
      "${AUTH_HEADER[@]}" \
      --data-urlencode "question=${QUESTION}_${i}" \
      ${CONVERSATION_ID:+--data-urlencode "conversationId=${CONVERSATION_ID}"} \
      --data-urlencode "deepThinking=false" \
      "${BASE_URL}/rag/v3/chat" \
      2>/dev/null
  ) &
done

wait
