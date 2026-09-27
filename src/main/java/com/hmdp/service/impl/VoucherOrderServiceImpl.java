package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seckill-order-consumer");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    private void init(){
        // 确保 Stream 与消费组存在（幂等：已存在时忽略异常），否则消费者 XREADGROUP 会一直 NOGROUP 报错
        ensureConsumerGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 幂等地创建秒杀订单 Stream 及其消费组。
     * XGROUP CREATE 在组已存在时会抛 BUSYGROUP，这里捕获并忽略。
     */
    private void ensureConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
        } catch (Exception e) {
            log.debug("秒杀订单消费组已存在或创建失败（忽略）: {}", e.getMessage());
        }
    }
    private class VoucherOrderHandler implements Runnable{

        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //1. 获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),//阻塞2秒，如果没有消息，返回空列表,每次只拿 1 条消息
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())//只读取那些从未分配给任何消费者的最新消息
                    );
                    //2.判断消息获取是否成功

                    if (list == null || list.isEmpty()){
                        continue;
                    }
                    //2.1如果获取失败，继续下一个循环

                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //2.2如果获取成功，可以下单

                    //ack确认、、
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());//告诉 Redis，这条订单我已经成功存入数据库了，你可以把这笔记录从我的“待处理清单”里划掉了。
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
        /*
         * 处理pending列表中的订单,当服务器重启后，第一个死循环会从最新的位置开始读，那条没处理成功的消息 A 就会被永远遗忘。
         */
        private void handlePendingList() {
            while (true){
                try {
                    //1. 获取pending列表中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))//只读取那些从未分配给任何消费者的最新消息，从 ID 为 0-0 的位置开始读取。
                    );
                    //2.判断消息获取是否成功

                    if (list == null || list.isEmpty()){
                        break;
                    }
                    //2.1如果获取失败，继续下一个循环

                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //2.2如果获取成功，可以下单

                    //ack确认、、
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending列表订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }


//    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024*1024);
//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    VoucherOrder voucherOrder = orderTask.take();
//
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {

            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单");
                return;
            }

    //        synchronized (userId.toString().intern() ) {
                //获取当前代理对象(事务)
            try {

                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }
    }

    private IVoucherOrderService proxy;

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

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.执行lua脚本
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        //2.判断结果是否为0
//        int r = result.intValue();
//        if (r != 0 ){
//            return Result.fail(r ==1 ? "库存不足" : "不允许重复下单");
//        }
//
//
//        //创建订单记录
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //6.1 生成订单id
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        //6.2 设置用户id
//        voucherOrder.setUserId(userId);
//
//        //6.3 设置优惠券id
//        voucherOrder.setVoucherId(voucherId);
//        orderTask.add(voucherOrder);
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        return Result.ok(orderId);
//
//
//
//
////        //1.查询优惠券
////        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
////        //2.判断秒杀是否开始
////        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
////            return Result.fail("秒杀未开始");
////        }
////        //3.判断秒杀是否结束
////        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
////            return Result.fail("秒杀已结束");
////        }
////
////        //4.判断库存是否充足
////        if (voucher.getStock() < 1){
////            return Result.fail("库存不足");
////        }
////
////        //先锁 再进行事务。提交完事务才释放锁
////        Long userId = UserHolder.getUser().getId();
////
////        //获取锁
////       //        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:"+userId);
////
////        RLock lock = redissonClient.getLock("lock:order:" + userId);
////        boolean isLock = lock.tryLock();
////        if (!isLock) {
////            return Result.fail("不允许重复下单");
////        }
////
//////        synchronized (userId.toString().intern() ) {
////            //获取当前代理对象(事务)
////        try {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        } finally {
////            lock.unlock();
////        }
//
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
//        Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();


        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        //判断是否存在
        if (count > 0) {
            log.error("已购买");
            return;
        }

        //5.更新库存 cas法
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")//set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where voucher_id = ? and stock = ?
                .update();

        //6.判断是否更新成功
        if (!success) {
            log.error("库存不足");
            return;
        }

//        //创建订单记录
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //6.1 生成订单id
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        //6.2 设置用户id
//        voucherOrder.setUserId(userId);
//
//        //6.3 设置优惠券id
//        voucherOrder.setVoucherId(VoucherOrder);

        save(voucherOrder);


    }

}
