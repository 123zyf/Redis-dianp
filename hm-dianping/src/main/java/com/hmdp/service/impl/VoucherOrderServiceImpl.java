package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zyf
 * @since 2024-2-12
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    //调用lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 用于线程池处理的任务
// 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    /**
     * 调用创建订单
     * @param voucherOrder
     */
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            proxy.createVoucherOrder(voucherOrder);
        }


    /**
     * 优惠券秒杀
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();

        //1、执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),     //key类型参数
                voucherId.toString(), userId.toString()
        );
        //2、判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1、不为0、没有购买资格
            return Result.fail(r==1 ? "库存不足":"不能重复下单");
        }
        //2.2、为0：有购买资格、将下单信息存到阻塞队列
        //todo 将下单信息存到阻塞队列
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);

        //获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //将下单信息存到阻塞队列
        orderTasks.add(voucherOrder);

        //3、返回订单id
        return Result.ok(orderId);
    }


    @Transactional
    public  void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        int c = count.intValue();
        // 5.2.判断是否存在
        if (c > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return ;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return ;
        }
        save(voucherOrder);

    }

    /**
     * 优惠券秒杀功能
     * @param voucherId
     * @return
     */
/*    @Override
    //@Transactional  //涉及到多张表的操作
    //由于将非查询操作放到其他方法中了、这里多表的修改、新增、等都不涉及到、可以不用事务了
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2判断秒杀是否开始
        //秒杀开始时间是否在当前时间之后
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //3判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4、判断库存是否充足
        if (voucher.getStock()<1){
            //库存不足
            return Result.fail("库存不足");
        }

        return createVoucherOrder(voucherId);
    }*/

/*    @Transactional
        public  Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        //分布式锁
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //todo Redisson实现分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        //boolean isLock = lock.tryLock(1200);
        //todo Redisson实现分布式锁
        boolean isLock = lock.tryLock(); // 无参：失败不等待、第一个参数：等待时间、第二个：锁释放的时间、 第三个：时间单位

        //判断锁是否获取成功
        if (!isLock){
            return Result.fail("不允许重复下单");
        }

        //todo 一人一单判断
        *//*
        依旧会出现多线程超卖问题
        因为乐观锁用于修改时判断已有数据和原先数据的差异
        而这里是插入操作、无法判断数据有没有修改过：因为数据在未插入前根本不存在、也就是说拿不到虚空值去判断
         *//*
        userId = UserHolder.getUser().getId();


        //intern():去字符串常量池里找、toString实际上是new了一个字符串（同一个id用了toString也会new新对象）
        *//*
        这里又有一个新问题：加在这里的话锁资源会在return后释放、
        而@Transactional是事务管理、在整个方法结束事务才会提交、若此时进来新进程、
        因为此时数据还没提交数据库、又有新进程进来修改可能又会引发多线程并发安全问题
         *//*
        //synchronized (userId.toString().intern()) { //这里选择用用户id作为锁、键范围限定到了用户id上、而不是整个范围
        try {
            //查询订单
            //订单表中查询用户id是否存在、优惠券id是否存在
            Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断是否存在
            if (count > 0) {
                return Result.fail("该用户已经购买过");
            }

            //todo 解决超卖问题：乐观锁：CAS法
            //5、扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    //.eq("voucher_id", voucherId)
                    *//*
                    问题：成功率太低了、原因：绝对的禁止了并发操作修改
                    只要发现被修改了就不执行、但是核心问题是库存依旧足够：却不让并发操作
                     *//*
                    //.eq("voucher_id", voucherId).eq("stock",voucher.getStock())
                    *//*
                    这里判断条件改成只要还有库存就执行修改
                     *//*
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }


            //6、创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1 订单id:     全局唯一ID
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);

            //6.2 用户id      UserHolder：封装threadLocal的类、在拦截器处记录用户信息
            //Long userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);

            //6.3 代金券id
            voucherOrder.setVoucherId(voucherId);

            //6.4 创建的订单信息写入数据库
            save(voucherOrder);
            //7、返回订单id
            return Result.ok(orderId);
        }finally {
            lock.unlock();
        }
    }*/
}
