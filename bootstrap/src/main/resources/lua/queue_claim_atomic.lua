-- 当请求位于允许的队头窗口内时，进行出队 claim；同时清理过期僵尸条目
-- KEYS[1]: 队列 ZSET Key
-- ARGV[1]: 请求 ID
-- ARGV[2]: 最大可进入的 rank（可用许可数）
-- ARGV[3]: entry 存活标记 Key 前缀（Java 侧已 set with TTL，缺失即视为僵尸）
local queueKey = KEYS[1]
local requestId = ARGV[1]
local maxRank = tonumber(ARGV[2])
local entryPrefix = ARGV[3]

-- 取头部窗口 + 额外 slack：slack 用于在僵尸密集时尽量推进存活条目至 maxRank 之内
local slack = 16
local headEntries = redis.call('ZRANGE', queueKey, 0, maxRank + slack - 1)

local liveRank = -1
local liveCount = 0
for i = 1, #headEntries do
    local member = headEntries[i]
    if redis.call('EXISTS', entryPrefix .. member) == 1 then
        if member == requestId then
            liveRank = liveCount
        end
        liveCount = liveCount + 1
    else
        -- 僵尸（Java 侧 entry 标记已 TTL 过期或被显式删除），从队列移除
        redis.call('ZREM', queueKey, member)
    end
end

-- 不在存活队头窗口内（要么不在队列、要么落在 maxRank 之外）
if liveRank < 0 or liveRank >= maxRank then return {0} end

-- 获取原始 score（便于必要时按原位次重入队）
local score = redis.call('ZSCORE', queueKey, requestId)

-- 出队 claim：同步删除 entry 标记，避免后续被自己误判
redis.call('ZREM', queueKey, requestId)
redis.call('DEL', entryPrefix .. requestId)

return {1, score}
