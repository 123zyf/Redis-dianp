package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zyf
 * @since 2024-2-12
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private FollowServiceImpl followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取发布blog的用户
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询用户是否被点赞:即打开页面列表的同时 就设置了用户的点赞状态
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户未登录、无需查询是否点赞
            return;
        }
        //1、获取用户id
        Long userId = user.getId();
        //2、查询用户是否点过赞
        String key = "blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3、设置状态
        blog.setIsLike(score!=null);
    }

    @Override
    public Result queryBlogById(Long id) {
        //1、查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //2、查询blog有关用户
        queryBlogUser(blog);
        //todo 用户点赞功能 查询判断
        //3、查询用户是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);

    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    //todo 用户点赞功能
    @Override
    public Result likeBlog(Long id) {
        //1、获取用户id
        Long userId = UserHolder.getUser().getId();
        //2、判断用户是否点赞过
        String key = "blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3、没有点赞过、可以点赞
        if (score == null) {
            //3.1：数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2：将用户消息保存到redis的set集合中（用于判断用户是否点赞过、redis存在用户说明点赞过）
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4、点赞过：无法点赞
            //4.1：数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2：将redis中的用户消息移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞用户列表
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:"+id;
        //1、查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2、解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3、根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    /**
     * 新增博客的同时推送给所有粉丝
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1、获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2、保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增失败");
        }

        //3、获取笔记作者粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4、推送笔记给所有粉丝(收件箱):redis的ZSET集合中key：粉丝id、value：发布的笔记id、按时间排序score：时间戳
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = "feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        //5、返回id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        //1、获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2、查询收件箱
        String key = "feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3、解析数据
        //存放blog的id的集合
        List<Long> ids = new ArrayList<>(typedTuples.size());

        //上次查询的最小分数（时间戳）
        long minTime = 0;
        //offset值：上次最小分数的重复个数
        int os = 1;

        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取blog的id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //4、根据id查询blog
        String idStr = StrUtil.join("," + ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELE(id,"+idStr+")").list();

        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //5、返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
