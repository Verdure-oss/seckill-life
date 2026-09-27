-- Seckill lua script: atomic stock decrement
-- Parameters:
--   ARGV[1] = voucherId
--   ARGV[2] = userId
--   ARGV[3] = orderId
--   ARGV[4] = timestamp (for record)

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

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