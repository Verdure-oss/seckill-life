package com.hmdp;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.impl.BlogServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 单元测试：探店博文点赞状态使用 Redis Pipeline 批量查询，避免逐条 N 次网络往返。
 */
class BlogLikedPipelineTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void fillBlogsLikedStatus_shouldBatchQueryViaPipeline() throws Exception {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        BlogServiceImpl service = new BlogServiceImpl();

        Field field = BlogServiceImpl.class.getDeclaredField("stringRedisTemplate");
        field.setAccessible(true);
        field.set(service, template);

        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);

        // 模拟 pipeline 返回：第 1、3 条已点赞（非 null），第 2 条未点赞（null）
        List<Object> scores = Arrays.asList(new byte[]{1}, null, new byte[]{1});
        doReturn(scores).when(template).executePipelined(any(RedisCallback.class));

        List<Blog> blogs = new ArrayList<>();
        for (long id : new long[]{1L, 2L, 3L}) {
            Blog blog = new Blog();
            blog.setId(id);
            blogs.add(blog);
        }

        Method method = BlogServiceImpl.class.getDeclaredMethod("fillBlogsLikedStatus", List.class);
        method.setAccessible(true);
        method.invoke(service, blogs);

        assertThat(blogs.get(0).getIsLike()).isTrue();
        assertThat(blogs.get(1).getIsLike()).isFalse();
        assertThat(blogs.get(2).getIsLike()).isTrue();
        // 仅发起一次 pipeline 调用（而非 3 次 score 查询）
        verify(template, times(1)).executePipelined(any(RedisCallback.class));
    }
}