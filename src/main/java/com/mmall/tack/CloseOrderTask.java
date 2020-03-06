package com.mmall.tack;

import com.mmall.common.Const;
import com.mmall.common.RedissonManager;
import com.mmall.dao.OrderMapper;
import com.mmall.service.IOrderService;
import com.mmall.util.PropertiesUtil;
import com.mmall.util.RedisShardedPoolUtil;
import javafx.beans.property.Property;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CloseOrderTask {

    @Autowired
    private IOrderService iOrderService;
    @Autowired
    private RedissonManager redissonManager;


    // 以当前时间为准，关闭当前时间以前两个小时的订单，每一分钟执行此方法一次
//    @Scheduled(cron = "0 */1 * * * ?") //每个一分钟的整数倍
    public void closeOrderTaskV1() {
        log.info("关闭订单定时任务启动");
        int hour = Integer.parseInt(PropertiesUtil.getProperty("close.order.task.time.hour", "2"));
        iOrderService.closeOrder(hour);
        log.info("关闭订单定时任务结束");
    }

    @PreDestroy
    private void delLock() {
        RedisShardedPoolUtil.del(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
    }
    /**
     * 此方法还是不能严格解决掉死锁问题，若将tomcat突然关闭，则会发生死锁，但此死锁可以通过上面注解@PreDestory来解决掉
     *                                   若将tomcat进程直接kill，则@PreDestory也不能解决掉死锁问题
     */
//    @Scheduled(cron = "0 */1 * * * ?") //每个一分钟的整数倍
    public void closeOrderTaskV2() {
        log.info("关闭订单定时任务启动");
        long lockTimeout = Integer.parseInt(PropertiesUtil.getProperty("lock.timeout", "50000"));
        Long setnxResult = RedisShardedPoolUtil.setnx(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, String.valueOf(System.currentTimeMillis() + lockTimeout));
        if (setnxResult != null && setnxResult.intValue() == 1) {
            // 返回值为1，代表设置成功，获得锁
            closeOrder(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
        } else {
            log.info("没有获得分布式锁：{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
        }
        log.info("关闭订单定时任务结束");
    }
    private void closeOrder(String lockName) {
        RedisShardedPoolUtil.expire(lockName, 5); // 设置锁的有效期为5秒，防止死锁
        log.info("获取{}, ThreadName:{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, Thread.currentThread().getName());
        int hour = Integer.parseInt(PropertiesUtil.getProperty("close.order.task.time.hour", "2"));
//        iOrderService.closeOrder(hour);
        RedisShardedPoolUtil.del(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
        log.info("释放{}，ThreadName:{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, Thread.currentThread().getName());
        log.info("===========================================================");
   }

//    @Scheduled(cron = "0 */1 * * * ?") //每个一分钟的整数倍
    public void closeOrderTaskV3() {
        log.info("关闭订单定时任务启动");
        long lockTimeout = Integer.parseInt(PropertiesUtil.getProperty("lock.timeout", "50000"));
        Long setnxResult = RedisShardedPoolUtil.setnx(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, String.valueOf(System.currentTimeMillis() + lockTimeout));
        if (setnxResult != null && setnxResult.intValue() == 1) {
            // 返回值为1，代表设置成功，获得锁
            closeOrder(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
        } else {
            // 未获取到锁，继续判断，判断时间戳，看是否可以重置并获取到锁
            String lockValueStr = RedisShardedPoolUtil.get(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
            if (lockValueStr != null && System.currentTimeMillis() > Long.valueOf(lockValueStr)) {
                String getSetResult = RedisShardedPoolUtil.getSet(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, String.valueOf(System.currentTimeMillis() + lockTimeout));
                // 再次用当前的时间戳getset
                // 返回给定key的旧值，判断旧值，是否可以获取锁
                // 当key没有旧值时，即key不存在时，返回nil，此时说明可以获取锁
                if (getSetResult == null || (getSetResult != null && StringUtils.equals(getSetResult, lockValueStr))) {
                    // 真正获取到锁
                    closeOrder(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
                } else {
                    log.info("没有获取到分布式锁：{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
                }
            } else {
                log.info("没有获取到分布式锁：{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
            }
        }
        log.info("关闭订单定时任务结束");

    }

    @Scheduled(cron = "0 */1 * * * ?") //每个一分钟的整数倍
    public void closeOrderTaskV4() {
        RLock rLock = redissonManager.getRedisson().getLock(Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK);
        boolean getLock = false;
        try {
            if (getLock = rLock.tryLock(0, 5, TimeUnit.SECONDS)) {
                log.info("Redisson获取到分布式锁:{}, ThreadName:{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, Thread.currentThread().getName());
                int hour = Integer.valueOf(PropertiesUtil.getProperty("close.order.task.time.hour", "2"));
//                iOrderService.closeOrder(hour);
            } else {
                log.info("Redisson没有获取到分布式锁:{}, ThreadName:{}", Const.REDIS_LOCK.CLOSED_ORDER_TASK_LOCK, Thread.currentThread().getName());
            }
        } catch (InterruptedException e) {
            log.error("Redisson没有获取到分布式锁", e);
        } finally {
            if (!getLock) {
                return ;
            }
            rLock.unlock();
            log.info("Redisson释放分布式锁");
        }

    }



}
