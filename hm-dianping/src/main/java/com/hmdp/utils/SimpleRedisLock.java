package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author zyf
 * @Data 2024/2/10 - 20:10
 */
public class SimpleRedisLock implements ILock{

    private String name;    //锁的名称
    private StringRedisTemplate stringRedisTemplate;
    //锁前缀
    public static final String KEY_PREFIX = "lock:";

    //todo 改进1,防止错误释放锁
    //锁的标识,这里用的是hutool工具类,true:去除uuid的_
    public static final String ID_PREFIX = UUID.fastUUID().toString(true)+"-";

    /*
    初始化lua脚本
     */
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //todo 改进1,释放锁的值拼接
        //获取当前线程标识 , 集群环境下可能出现id相同的情况
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁
        //setIfAbsent等价于set nx   timeoutSec参数相当于超时时间 ,组合在一起相当于set nx ex
        Boolean success = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //返回结果：是否获取锁
        // 防止自动拆箱的时候出现空指针异常
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        //todo 改进1.释放锁代码修改
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标识是否一致
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }

    /**
     * lua脚本释放锁：为判断锁标识和释放锁的操作添加原子性
     */
    public void unlocks(){
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
