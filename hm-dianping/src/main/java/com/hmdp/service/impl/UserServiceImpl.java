package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zyf
 * @since 2024-2-12
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1、校验手机号码
        if(RegexUtils.isPhoneInvalid(phone)){
            //号码格式错误
            return Result.fail("手机号格式错误！");
        }

        //2、号码格式没错、生成校验码
        String code = RandomUtil.randomNumbers(6);  //hutool工具类：自动生成随机数

        /*
        3、保存校验码到session:用于与用户提交的校验码进行校验
        session.setAttribute("code",code);
        */
        /*
        session存在集群共享问题：多台tomcat服务器不共享session存储空间（请求切换到不同tomcat服务时会导致数据丢失）
        因此这里选择保存到redis内存实现共享
         */
        //保存验证码到redis、key用手机号码、value用验证码,key加一个前缀表示涉及的业务
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);


        //4、发送校验码
        log.info("发送短信校验码成功：{}",code);
        //5、返回结果
        return Result.ok();
    }

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、校验电话号码格式
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //号码格式错误
            return Result.fail("手机号格式错误！");
        }
        //2、校验验证码是否正确
        /*
        String originCode =(String) session.getAttribute("code");   //获取注册时的验证码
        String code = loginForm.getCode();  //登录输入的验证码
        */
        //redis解决session共享
        String originCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (originCode == null || !code.equals(originCode)){    //验证码超时或验证码错误
            return Result.fail("验证码出错");
        }

        //3、根据手机号码获取用户信息
        User user = query().eq("phone", phone).one();

        //4、如果没查到、注册新用户、添加数据库、存入session
        if (user == null){
            user = createUserWithPhone(phone);  //这里不创建新变量：可以减少一步重复存入session的操作
            save(user);
        }
        /*
        5、如果查到、存入session
        session.setAttribute("user",user);    这种方式不安全：所有信息都存进去了、因此要先进行脱敏
        session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class) );//这里是hutool的工具类
        */
        //修改
       /*
       将随机生成的token作为key、将用户信息存入hash结构
       为避免过多访问服务器使用put方法、选择使用putAll方法一次性将用户所有信息存入
       因此又涉及到将脱敏后的对象DTO封装为Map对象：使用hutool工具类实现
        */
        //5、redis解决session共享
        //5.1、生成token
        String token = UUID.randomUUID().toString(true);

        //5.2、将DTO转为Map集合
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //直接转换会出错：DTO的id是Long而stringRedisTemplate要求key、value都是string
        //Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()    //自定义内容
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString())
                );
        //5.3、存储用户值
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //5.4、设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //6、返回结果
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        return user;
    }
}
