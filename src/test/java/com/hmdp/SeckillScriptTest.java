package com.hmdp;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SimpleRedisLock;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：防止秒杀 Lua 脚本引用路径、库存 key 前缀、消费者线程启动等
 * 之前修复过的问题再次被破坏。不依赖 Redis/MySQL。
 */
class SeckillScriptTest {

    private String readResource(String path) throws Exception {
        Resource r = new ClassPathResource(path);
        assertThat(r.exists()).as("资源应存在: " + path).isTrue();
        return StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
    }

    /** 1. 秒杀脚本应加载 seckill.lua（曾错误引用不存在的 lock.lua） */
    @Test
    void seckillScript_shouldPointToSeckillLua() throws Exception {
        Field field = VoucherOrderServiceImpl.class.getDeclaredField("SECKILL_SCRIPT");
        field.setAccessible(true);
        DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) field.get(null);

        // seckill.lua 的显著内容（库存 key 前缀带冒号），而 lock.lua 不存在
        String scriptContent = script.getScriptAsString();
        assertThat(scriptContent).contains("seckill:stock:");
        assertThat(scriptContent).contains("stream.orders");
    }

    /** 2. 解锁脚本应加载 unlock.lua（曾错误引用 lock.lua） */
    @Test
    void unlockScript_shouldPointToUnlockLua() throws Exception {
        Field field = SimpleRedisLock.class.getDeclaredField("UNLOCK_SCRIPT");
        field.setAccessible(true);
        DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) field.get(null);

        // unlock.lua 的显著内容（KEYS[1] 解锁逻辑），而 lock.lua 不存在
        String scriptContent = script.getScriptAsString();
        assertThat(scriptContent).contains("KEYS[1]");
        assertThat(scriptContent).doesNotContain("seckill");
    }

    /** 3. seckill.lua 中的库存 key 前缀应与 Java 端 RedisConstants 一致（冒号） */
    @Test
    void seckillLua_stockKeyPrefix_shouldMatchJavaConstant() throws Exception {
        String lua = readResource("seckill.lua");

        // Lua 中使用的 key 前缀（含冒号），与 Java 端常量保持一致
        assertThat(lua).contains("'seckill:stock:' .. voucherId");
        assertThat(lua).contains("'seckill:order:' .. voucherId");

        // Java 端常量
        assertThat(RedisConstants.SECKILL_STOCK_KEY).isEqualTo("seckill:stock:");
    }

    /** 4. unlock.lua 中应为 KEYS（大写），而不是 KEYs */
    @Test
    void unlockLua_shouldUseUppercaseKeys() throws Exception {
        String lua = readResource("unlock.lua");

        assertThat(lua).contains("KEYS[1]");
        assertThat(lua).doesNotContain("KEYs[1]");
    }

    /** 5. 秒杀订单消费者线程应通过 @PostConstruct 启动（曾因缺失而永不消费） */
    @Test
    void consumerThread_shouldBeStartedViaPostConstruct() {
        boolean hasPostConstructOnInit = Arrays.stream(VoucherOrderServiceImpl.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("init"))
                .anyMatch(m -> m.isAnnotationPresent(PostConstruct.class));

        assertThat(hasPostConstructOnInit)
                .as("VoucherOrderServiceImpl.init() 应标注 @PostConstruct")
                .isTrue();
    }
}