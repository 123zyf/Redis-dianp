package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    /**
     * 优惠券秒杀功能
     * @param voucherId
     * @return
     */
    @Override
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
        Long userId = UserHolder.getUser().getId();
        //逻辑：先获取锁、再提交事务、然后才释放锁
        /*
        事务要想生效：spring对当前类做了动态代理、拿到代理对象、用这个对象做的事务处理
        而这里的createVoucherOrder(voucherId);实际上是this.createVoucherOrder(voucherId);
        即不是代理对象、而是目标对象（没有事务功能）
         */
        synchronized (userId.toString().intern()) {
            //拿到当前对象的代理对象:添加依赖  启动类添加注释
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //todo 一人一单判断
        /*
        依旧会出现多线程超卖问题
        因为乐观锁用于修改时判断已有数据和原先数据的差异
        而这里是插入操作、无法判断数据有没有修改过：因为数据在未插入前根本不存在、也就是说拿不到虚空值去判断
         */
        Long userId = UserHolder.getUser().getId();
        //intern():去字符串常量池里找、toString实际上是new了一个字符串（同一个id用了toString也会new新对象）
        /*
        这里又有一个新问题：加在这里的话锁资源会在return后释放、
        而@Transactional是事务管理、在整个方法结束事务才会提交、若此时进来新进程、
        因为此时数据还没提交数据库、又有新进程进来修改可能又会引发多线程并发安全问题
         */
        //synchronized (userId.toString().intern()) { //这里选择用用户id作为锁、键范围限定到了用户id上、而不是整个范围
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
                    /*
                    问题：成功率太高了、原因：绝对的禁止了并发操作修改
                    只要发现被修改了就不执行、但是核心问题是库存依旧足够：却不让并发操作
                     */
                    //.eq("voucher_id", voucherId).eq("stock",voucher.getStock())
                    /*
                    这里判断条件改成只要还有库存就执行修改
                     */
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
            voucherOrder.setUserId(userId);

            //6.3 代金券id
            voucherOrder.setVoucherId(voucherId);

            //6.4 创建的订单信息写入数据库
            save(voucherOrder);
            //7、返回订单id
            return Result.ok(orderId);
       // }
    }
}
