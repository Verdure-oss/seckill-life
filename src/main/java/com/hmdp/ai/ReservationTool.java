package com.hmdp.ai;

import com.hmdp.utils.RedisIdWorker;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * AI Function Tool: Make a reservation at a shop.
 */
@Component
public class ReservationTool {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 预约到店
     * @param shopId 店铺ID
     * @param reservationTime 预约时间 (格式: yyyy-MM-dd HH:mm)
     * @param peopleCount 用户人数
     * @return 预约结果
     */
    @Tool(name = "makeReservation", value = "预约到店就餐。参数shopId是店铺ID，reservationTime是预约时间(格式:yyyy-MM-dd HH:mm)，peopleCount是用餐人数。返回预约ID和结果。")
    public Map<String, Object> makeReservation(Long shopId, String reservationTime, Integer peopleCount) {
        try {
            // 生成预约ID
            Long reservationId = redisIdWorker.nextId("reservation");

            // 解析预约时间
            LocalDateTime dateTime = LocalDateTime.parse(reservationTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            long timestamp = dateTime.toEpochSecond(ZoneOffset.UTC);

            // 存储预约信息到Redis
            String key = "reservation:" + reservationId;
            Map<String, String> reservation = new HashMap<>();
            reservation.put("id", reservationId.toString());
            reservation.put("shopId", shopId.toString());
            reservation.put("reservationTime", reservationTime);
            reservation.put("peopleCount", peopleCount.toString());
            reservation.put("createTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            stringRedisTemplate.opsForHash().putAll(key, reservation);
            // 设置过期时间为预约时间之后1天
            stringRedisTemplate.expire(key, 1, java.util.concurrent.TimeUnit.DAYS);

            // 返回预约结果
            Map<String, Object> result = new HashMap<>();
            result.put("reservationId", reservationId);
            result.put("message", "预约成功");
            result.put("shopId", shopId);
            result.put("reservationTime", reservationTime);
            result.put("peopleCount", peopleCount);

            return result;
        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("reservationId", -1);
            errorResult.put("message", "预约失败: " + e.getMessage());
            return errorResult;
        }
    }
}