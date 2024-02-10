package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;   //逻辑过期时间
    private Object data;    //是要存入redis的万能数据：不需要修改其他原代码
}
