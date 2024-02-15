package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zyf
 * @since 2024-2-12
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    /**
     * 点赞功能
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 点赞用户列表
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 新增博客
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
