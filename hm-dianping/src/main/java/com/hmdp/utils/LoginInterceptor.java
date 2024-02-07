package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author zyf
 * @Data 2024/2/7 - 19:47
 */
public class LoginInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor() {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 登陆校验功能的拦截器
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*
        1、获取session
        HttpSession session = request.getSession();
        *//*
        //1、从请求头中获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            response.setStatus(401);    //设置为空状态码
            return false;
        }

        *//*
        2、从session中获取用户
        Object user = session.getAttribute("user");
        *//*
        //2、根据token从redis获取用户信息
        String key = LOGIN_USER_KEY+token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);  //entries:获取redis的多个字段值
        //3、对象为空：拦截
        if (userMap.isEmpty()){
            response.setStatus(401);    //设置为空状态码
            return false;
        }

        //4、将查到的Hash数据转为DTO对象、方便后续存储对象信息到threadLoacl
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //5、对象不为空：保存对象信息到threadLocal
        UserHolder.saveUser(userDTO);

        //6、刷新token有效期：设置在拦截器只要访问了需要拦截器的地方就刷新：需优化
        *//*
        要实现只要用户访问页面就重新刷新token有效期
        而不是从登录那一刻就开始倒数有效期
         *//*
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //7、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();    //释放threadLocal内容、防止内存泄露

    }*/
        /*
        这个拦截器就是专门用于判断是否需要拦截的
         */
        if (UserHolder.getUser() == null) {
            //如果threadLocal没有存入对象
            response.setStatus(401);
            return false;
        }
        //如果存入了对象
        return true;
    }
}
