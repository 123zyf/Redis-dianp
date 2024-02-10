package com.hmdp.utils;

/**
 * @author zyf
 * @Data 2024/2/10 - 20:08
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
