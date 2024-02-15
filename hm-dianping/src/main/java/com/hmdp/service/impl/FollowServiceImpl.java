package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zyf
 * @since 2024-2-12
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    /**
     * 关注、取关用户
     *
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        //1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        String key = "follows:" + userId;

        //2、判断是关注还是取关
        if (isFollow) {
            //2.1关注：向数据库表添加新用户
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            //todo 关注用户的同时将本地用户和关注的用户1对多关系放到redis的set集合中、方便实现后续的共同关注功能
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //2.2取关：删除表中数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));

            //移除关注集合中的用户对象
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 打开页面前判断用户是否已被关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        //1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2、查询是否关注
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        Integer c = count.intValue();
        //3、判断
        return Result.ok(c > 0);
    }

    /**
     * 共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        //1、获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2、求交集
        String key2 = "follows:" + id;  //目标用户的id
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        //若无交集
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        //3解析用户
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4、查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
