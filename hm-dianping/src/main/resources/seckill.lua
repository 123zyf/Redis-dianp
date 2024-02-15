---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by zyf.
--- DateTime: 2024/2/12 10:18
---

--1、参数列表
--1.1、优惠卷id
local voucherId = ARGV[1]
--1.2、用户id
local userId  = ARGV[2]

--2、数据key
--2.1、库存key
local stockKey = 'seckill:stock:'+voucherId
--2.2、订单key
local orderKey = 'seckill:order:'+voucherId

--3、脚本业务
--3.1、判断库存是否充足
if (tonumber(redis.call('get' , stockKey)) <= 0) then
    --库存不足
    return 1
end
--3.2、判断用户是否过下单
if (redis.call('sismember',orderKey,userId)) then
    --已下过单
    return 2
end
--3.4、扣库存
redis.call('incrby',stockKey,-1)
--3.5、添加订单信息
redis.call('sadd',orderKey,userId)
return 0