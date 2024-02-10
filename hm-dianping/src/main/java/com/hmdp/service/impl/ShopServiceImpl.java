package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zyf
 * @since 2024-2-12
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //queryWithPassThrough(id);
        //cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,id2->getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id为空");
        }
        //todo 缓存更新
        //1、更新数据库信息
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ id);
        return Result.ok();
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

    /**
     * 缓存穿透的代码逻辑
     * @param id
     * @return
     */
 /*   public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY+id;  //为id添加前缀整体作为key
        //1、从redis缓存查询是否有数据
        *//*
        这里选择用id作为redis的key、商铺信息作为value、id保证了数据唯一性
        同时、因为使用的是stringRedisTemplate，返回的是string
        需要先将其反序列化为Shop对象
         *//*
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、如果有，直接返回数据、这里的判断时是判断是否有具体值、而如果为空值、则跳过
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//对象的反序列化
            return shop;
        }

        //todo 缓存穿透问题：判断缓存命中
        if (shopJson != null){  //上面的if判断完、此时的对象有两种可能：没有命中或空值
            //空值
            return null;
        }

        //3、如果没有，查询数据库
        Shop shop = this.getById(id); //mybatis-plus

        //4、数据库是否能查到数据：如果查不到、说明没有数据
        if (shop == null){
            //todo 解决缓存穿透的问题：使用缓存空对象
            //stringRedisTemplate.opsForValue().set(key,null,CACHE_NULL_TTL, TimeUnit.MINUTES);
            //todo 为redis缓存的ttl添加一个随机数、防止缓存雪崩
            stringRedisTemplate.opsForValue().set(key,null,CACHE_NULL_TTL+Long.getLong(RandomUtil.randomNumbers(6)), TimeUnit.MINUTES);
            return null;
        }

        //5、如果查到数据：将数据添加到redis缓存中
        String jsonStr = JSONUtil.toJsonStr(shop);  //将user对象序列化为json
        // stringRedisTemplate.opsForValue().set(key,jsonStr);

        //实现了缓存更新策略的读操作（没有修改）：使用超时剔除方案、添加一个超时时间
        //todo 缓存更新策略
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(jsonStr),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //6、返回数据
        return shop;
    }

 */   /**
     * 互斥锁解决缓存击穿问题代码逻辑
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = "cache:shop:"+id;  //为id添加前缀整体作为key
        //1、从redis缓存查询是否有数据
        /*
        这里选择用id作为redis的key、商铺信息作为value、id保证了数据唯一性
        同时、因为使用的是stringRedisTemplate，返回的是string
        需要先将其反序列化为Shop对象
         */
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、如果有，直接返回数据、这里的判断时是判断是否有具体值、而如果为空值、则跳过
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//对象的反序列化
            return shop;
        }

        //todo 缓存穿透问题：判断缓存命中
        if (shopJson != null){  //上面的if判断完、此时的对象有两种可能：没有命中或空值
            //空值
            return null;
        }

        //3、如果缓存没数据、尝试获取互斥锁
        //todo 缓存击穿解决：互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null; //mybatis-plus
        try {
            //a、获取互斥锁
            boolean isLock = tryLock(lockKey);
            //b、判断是否获取成功：失败：休眠等待重新向redis查询商铺缓存
            if (!isLock){
                Thread.sleep(50);
                //休眠结束又要重新向redis查询缓存、即递归实现
                return queryWithMutex(id);
            }
            //c、成功做二次检查：重新查询redis缓存是否存在数据、存在则不需要重建缓存、否则：根据id查询数据库、将数据存入缓存、释放锁资源
            String newShopJson = stringRedisTemplate.opsForValue().get(key);

            //如果有，直接返回数据、这里的判断时是判断是否有具体值、而如果为空值、则跳过
            if (StrUtil.isNotBlank(shopJson)){
                shop = JSONUtil.toBean(newShopJson, Shop.class);//对象的反序列化
                return shop;
            }

            //4、如果没有，查询数据库
            shop = this.getById(id);

            //5、数据库是否能查到数据：如果查不到、说明没有数据
            if (shop == null){
                //todo 解决缓存穿透的问题：使用缓存空对象
                //stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                //todo 为redis缓存的ttl添加一个随机数、防止缓存雪崩
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL+Long.getLong(RandomUtil.randomNumbers(6)), TimeUnit.MINUTES);
                return null;
            }

            //6、如果查到数据：将数据添加到redis缓存中
            String jsonStr = JSONUtil.toJsonStr(shop);  //将user对象序列化为json
            // stringRedisTemplate.opsForValue().set(key,jsonStr);

            //实现了缓存更新策略的读操作（没有修改）：使用超时剔除方案、添加一个超时时间
            //todo 缓存更新策略
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(jsonStr),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //todo 释放锁
            unlock(lockKey);
        }


        //7、返回数据
        return shop;
    }

/*    *//**
     * 数据预热（重建缓存信息）
     * 存储店铺信息、逻辑过期时间的方法
     * @param id
     *//*
    public void saveShop2Redis(Long id , Long expireSeconds){
        //1、查询店铺信息
        Shop shop = getById(id);
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    //创建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    *//**
     * 逻辑过期实现缓存击穿
     * @param id
     * @return
     *//*
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY+id;  //为id添加前缀整体作为key
        //1、从redis缓存查询是否有数据
        *//*
        这里选择用id作为redis的key、商铺信息作为value、id保证了数据唯一性
        同时、因为使用的是stringRedisTemplate，返回的是string
        需要先将其反序列化为Shop对象
         *//*
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2、逻辑过期：如果未命中、返回空
        if (StrUtil.isBlank(shopJson)){
            return null;
        }

        //3、命中、（JSON反序列化）、判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData(); //redisData定义的data是Obj类型、这样操作方便转换类型
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //获取设置的过期时间
        LocalDateTime expireTime = redisData.getExpireTime();

        //3.1、没有过期、返回店铺信息
        //过期时间是否在当前时间之后、若是、没有过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
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
                    this.saveShop2Redis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //4.3、返回逻辑过期数据
        return shop;
    }*/
}
