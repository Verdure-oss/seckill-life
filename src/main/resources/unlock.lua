--比较线程标示与锁中的标示是否一致
if(redis.call('get',KEYs[1])==ARGV[1]) then
    --释放锁 delkey
    return redis.call('del',KEYs[1])
end
return 0