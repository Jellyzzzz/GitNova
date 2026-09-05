local capacity = tonumber(ARGV[1])
local refill_per_millisecond = tonumber(ARGV[2])
local request_cost = tonumber(ARGV[3])
local idle_ttl_millis = tonumber(ARGV[4])

local redis_time = redis.call('TIME')
local now_millis = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
local state = redis.call('HMGET', KEYS[1], 'available_permits', 'last_refill_millis')

local available = tonumber(state[1])
local last_refill = tonumber(state[2])
if available == nil or last_refill == nil then
    available = capacity
    last_refill = now_millis
end

local elapsed = math.max(0, now_millis - last_refill)
available = math.min(capacity, available + elapsed * refill_per_millisecond)

local allowed = 0
local retry_after_millis = 0
if available >= request_cost then
    allowed = 1
    available = available - request_cost
else
    retry_after_millis = math.ceil((request_cost - available) / refill_per_millisecond)
end

redis.call(
    'HSET',
    KEYS[1],
    'available_permits', tostring(available),
    'last_refill_millis', tostring(now_millis)
)
redis.call('PEXPIRE', KEYS[1], idle_ttl_millis)

return {allowed, math.floor(available), retry_after_millis}
