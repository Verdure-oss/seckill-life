-- 取消秒杀订单：原子恢复库存并移除“已购”标记，允许用户重新抢购
-- ARGV[1] = voucherId
-- ARGV[2] = userId
--
-- 只有在用户确实存在于“已购”集合时才恢复库存，避免重复取消导致库存虚增：
--   srem 返回 1 表示本次真正移除了成员，返回 0 表示原本就不在集合中。

local stockKey = 'seckill:stock:' .. ARGV[1]
local orderKey = 'seckill:order:' .. ARGV[1]

local removed = redis.call('srem', orderKey, ARGV[2])
if removed == 1 then
    redis.call('incrby', stockKey, 1)
end

return removed