-- 当请求位于允许的队头窗口内时，进行出队 claim。
-- KEYS[1]: 队列 ZSET Key
-- ARGV[1]: 请求 ID
-- ARGV[2]: 最大可进入的 rank（可用许可数）
local queueKey = KEYS[1]
local requestId = ARGV[1]
local maxRank = tonumber(ARGV[2])

-- 获取请求在队列中的排名
local rank = redis.call('ZRANK', queueKey, requestId)

-- 不在队列中或不在队头窗口
if not rank then return {0} end
if rank >= maxRank then return {0} end

-- 获取原始 score，便于必要时重新入队
local score = redis.call('ZSCORE', queueKey, requestId)

-- 从队列移除（已 claim）
redis.call('ZREM', queueKey, requestId)

-- 返回是否 claim 以及原始 score
return {1, score}
