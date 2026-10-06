-- Seckill lua script: atomic stock decrement with time-window validation
-- Parameters:
--   ARGV[1] = voucherId
--   ARGV[2] = userId
--   ARGV[3] = orderId
--   ARGV[4] = nowMs (current epoch millis)
--   ARGV[5] = beginMs (seckill window start, 0 = no limit)
--   ARGV[6] = endMs   (seckill window end, 0 = no limit)
--
-- Return codes:
--   0 = success
--   1 = out of stock
--   2 = already purchased
--   3 = seckill not started yet
--   4 = seckill already ended

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local nowMs = tonumber(ARGV[4])
local beginMs = tonumber(ARGV[5])
local endMs = tonumber(ARGV[6])

-- Time window validation (0 means unlimited)
if beginMs ~= 0 and nowMs < beginMs then
    return 3  -- Not started
end
if endMs ~= 0 and nowMs > endMs then
    return 4  -- Ended
end

-- Stock key
local stockKey = 'seckill:stock:' .. voucherId

-- Order key (to prevent duplicates)
local orderKey = 'seckill:order:' .. voucherId

-- Check stock
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1  -- Out of stock
end

-- Check if already purchased
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2  -- Already purchased
end

-- Decrement stock and record order
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

-- Return success (orderId is handled at Java layer)
return 0