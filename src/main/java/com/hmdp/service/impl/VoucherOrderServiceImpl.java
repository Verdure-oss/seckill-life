package com.hmdp.service.impl;

import com.hmdp.config.RabbitConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 自注入代理，确保 @Transactional 在监听器方法（target 调用）中也能生效 */
    @Lazy
    @Resource
    private IVoucherOrderService self;

    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        //生成订单id
        Long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不允许重复下单");
        }

        //3.发布消息到RabbitMQ实现异步下单
        Map<String, Object> orderMessage = new HashMap<>();
        orderMessage.put("orderId", orderId);
        orderMessage.put("userId", userId);
        orderMessage.put("voucherId", voucherId);

        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.SECKILL_ORDER_EXCHANGE,
                    RabbitConfig.SECKILL_ORDER_ROUTING_KEY,
                    orderMessage
            );
        } catch (Exception e) {
            log.error("发布秒杀订单消息到RabbitMQ失败", e);
            return Result.fail("下单失败，请稍后重试");
        }

        return Result.ok(orderId);
    }

    /**
     * RabbitMQ消费者：异步处理订单消息
     */
    @RabbitListener(queues = RabbitConfig.SECKILL_ORDER_QUEUE)
    public void handleVoucherOrderMessage(Map<String, Object> orderMessage) {
        if (orderMessage == null || orderMessage.isEmpty()) {
            log.warn("收到空订单消息，忽略处理");
            return;
        }

        Long userId = Long.valueOf(orderMessage.get("userId").toString());
        Long orderId = Long.valueOf(orderMessage.get("orderId").toString());
        Long voucherId = Long.valueOf(orderMessage.get("voucherId").toString());

        // 构造订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 使用分布式锁防止重复下单
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.warn("用户 {} 已存在下单操作，忽略重复消息", userId);
            return;
        }

        try {
            self.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 1. 先插入订单，依赖 (user_id, voucher_id) 唯一索引作为最终幂等兜底。
        //    一旦并发/异常造成重复消息，此处会抛出 DuplicateKeyException，直接视为已下单。
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            log.warn("重复下单拦截（唯一索引兜底）: userId={}, voucherId={}", userId, voucherId);
            return;
        }

        // 2. 扣减库存（CAS 乐观更新，需保证 stock > 0）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // where voucher_id = ? and stock > 0
                .update();

        // 3. 库存不足则回滚（订单插入也会随之回滚）
        if (!success) {
            log.error("库存不足，回滚订单: userId={}, voucherId={}", userId, voucherId);
            throw new RuntimeException("库存不足");
        }
    }
}
