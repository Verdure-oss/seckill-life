package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutsec 超时时间，单位秒
     * @return 是否获取到锁
     */
    boolean tryLock(long timeoutsec);


    /**
     * 解锁
     */
    void unlock();
}
