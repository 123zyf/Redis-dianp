package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author zyf
 * @Data 2024/2/9 - 13:37
 */

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     * 解决缓存穿透的set键方法
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key , Object value , Long time , TimeUnit unit){
        //time:键的TTL时间值     unit：时间的单位
        //传的是object的value、放到redis要序列化：JSONUtil.toJsonStr(value
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 解决缓存击穿的set键方法
     * @param key
     * @param data
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key,Object data,Long time,TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(data);
        //设置过期时间、不知道传过来的是什么单位：选择统一将单位转换为秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透的代码逻辑
     * @param keyPrefix key的前缀
     * @param id
     * @param type 需要操作的对象类型
     * @param dbFallback 一个存放数据库操作逻辑的函数
     * @param time  设置过期时间的值
     * @param unit  设置过期时间的单位
     * @return
     * @param <R>
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix , ID id , Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix+id;  //为id添加前缀整体作为key
        //1、从redis缓存查询是否有数据
        /*
        这里选择用id作为redis的key、商铺信息作为value、id保证了数据唯一性
        同时、因为使用的是stringRedisTemplate，返回的是string
        需要先将其反序列化为Shop对象
         */
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、如果有，直接返回数据、这里的判断时是判断是否有具体值、而如果为空值、则跳过
        if (StrUtil.isNotBlank(json)){
             //对象的反序列化
            return JSONUtil.toBean(json, type);
        }

        //todo 缓存穿透问题：判断缓存命中
        if (json != null){  //上面的if判断完、此时的对象有两种可能：没有命中或空值
            //空值
            return null;
        }

        //3、如果没有，查询数据库
        //Shop shop = this.getById(id); //这里根据不同的对象类型数据库的操作方法不同、因此无法在这里进行具体操作
        R r = dbFallback.apply(id);
        //4、数据库是否能查到数据：如果查不到、说明没有数据
        if (r == null){
            //todo 解决缓存穿透的问题：使用缓存空对象
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //todo 为redis缓存的ttl添加一个随机数、防止缓存雪崩
            //stringRedisTemplate.opsForValue().set(key,null,CACHE_NULL_TTL+Long.getLong(RandomUtil.randomNumbers(6)), TimeUnit.MINUTES);
            return null;
        }

        //5、如果查到数据：将数据添加到redis缓存中
        //String jsonStr = JSONUtil.toJsonStr(shop);  //将user对象序列化为json
        // stringRedisTemplate.opsForValue().set(key,jsonStr);

        //实现了缓存更新策略的读操作（没有修改）：使用超时剔除方案、添加一个超时时间
        //todo 缓存更新策略
        //stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        this.set(key,r,time,unit);
        //6、返回数据
        return r;
    }
    //创建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 逻辑过期实现缓存击穿
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R>dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix+id;  //为id添加前缀整体作为key
        //1、从redis缓存查询是否有数据
        /*
        这里选择用id作为redis的key、商铺信息作为value、id保证了数据唯一性
        同时、因为使用的是stringRedisTemplate，返回的是string
        需要先将其反序列化为Shop对象
         */
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、逻辑过期：如果未命中、返回空
        if (StrUtil.isBlank(json)){
            return null;
        }

        //3、命中、（JSON反序列化）、判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData(); //redisData定义的data是Obj类型、这样操作方便转换类型
        R r = JSONUtil.toBean(data, type);
        //获取设置的过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        //3.1、没有过期、返回店铺信息
        //过期时间是否在当前时间之后、若是、没有过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }

        //4、过期、缓存重建：获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //4.1、判断是否获取到锁
        if(isLock) {

            //4.2、如果获取到锁、开启独立线程、返回逻辑过期数据、返回逻辑过期时间
            //线程池对象
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存、缓存插入的不再是shop、而是redisData
                   // this.saveShop2Redis(id, 20L);
                    //查询数据库
                    R r1 = dbFallback.apply(id);

                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //4.3、返回逻辑过期数据
        return r;
    }

    /**
     * 互斥锁解决缓存击穿问题
     * 上锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        //向redis存入一个数据作为逻辑锁、使用字符串类型的SETNX操作：redis数据库没有目标数据才会执行新增（这里的值随意即可）
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //上面语句返回的flag是一个引用类型的Boolean，若直接返回、系统会自动拆箱后返回、可能造成空指针异常
        //return flag;
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
