package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一id生成器
 * @author zyf
 * @Data 2024/2/10 - 10:25
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1704067200L;

    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public long nextId(String keyPrefix){
        //1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//当前秒数
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2、生产序列号
        //2.1、获取当天日期：为key添加一个每天日期的后缀、即不会超出范围、还能统计每天的单数
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //2.2、自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3、拼接
        /*
        timestamp << COUNT_BITS:生成的id64位、时间戳左移32位留下32位给序列号
        左移补0、剩下的用或运算保留的都是序列号
         */
        return timestamp << COUNT_BITS | count;
    }

    /**
     * 用于生成时间戳
     * @param args
     */
/*    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }*/
}
