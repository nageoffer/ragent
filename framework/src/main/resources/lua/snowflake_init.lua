local hashKey = KEYS[1]
local dataCenterIdKey = 'dataCenterId'
local workIdKey = 'workId'

if (redis.call('exists', hashKey) == 0) then
    redis.call('hincrby', hashKey, dataCenterIdKey, 0)
    redis.call('hincrby', hashKey, workIdKey, 0)
    return { 0, 0 }
end

local dataCenterId = tonumber(redis.call('hget', hashKey, dataCenterIdKey))
local workId = tonumber(redis.call('hget', hashKey, workIdKey))

local max = 31
local resultWorkId = 0
local resultDataCenterId = 0

if (dataCenterId == max and workId == max) then
    redis.call('hset', hashKey, dataCenterIdKey, '0')
    redis.call('hset', hashKey, workIdKey, '0')
    resultWorkId = 0
    resultDataCenterId = 0
elseif (workId ~= max) then
    resultWorkId = redis.call('hincrby', hashKey, workIdKey, 1)
    resultDataCenterId = dataCenterId
elseif (dataCenterId ~= max) then
    resultWorkId = 0
    resultDataCenterId = redis.call('hincrby', hashKey, dataCenterIdKey, 1)
    redis.call('hset', hashKey, workIdKey, '0')
end

return { resultWorkId, resultDataCenterId }
