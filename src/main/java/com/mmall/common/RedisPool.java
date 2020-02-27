package com.mmall.common;

import com.mmall.util.PropertiesUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisPool {
    private static JedisPool pool;     // jedis连接池
    private static Integer maxTotal = Integer.parseInt(PropertiesUtil.getProperty("redis.max.total", "20")); // 最大连接数
    private static Integer maxIdle = Integer.parseInt(PropertiesUtil.getProperty("redis.max.idle", "10")); // 在jedispool中最大的idle（空闲）状态的jedis实例的个数
    private static Integer minIdle = Integer.parseInt(PropertiesUtil.getProperty("redis.max.idle", "2")); // 在jedispool中最小的idle（空闲）状态的jedis实例的个数
    private static Boolean testOnBorrow = Boolean.parseBoolean(PropertiesUtil.getProperty("redis.test.borrow", "true")); // 在borrow一个jedis实例的时候，是否要进行验证操作，如果为true，则得到的jedis实例一定是可用的
    private static Boolean testOnReturn = Boolean.parseBoolean(PropertiesUtil.getProperty("redis.test.return", "true")); // 在return一个jedis实例的时候，是否要进行验证操作，如果为true，则放回jedispool中的jedis实例一定是可用的
    private static String redisIp = PropertiesUtil.getProperty("redis2.ip");
    private static Integer redisPort = Integer.parseInt(PropertiesUtil.getProperty("redis2.port"));

    private static void init() {
        JedisPoolConfig config = new JedisPoolConfig();

        config.setMaxTotal(maxTotal);
        config.setMaxIdle(maxIdle);
        config.setMinIdle(minIdle);

        config.setTestOnBorrow(testOnBorrow);
        config.setTestOnReturn(testOnReturn);

        config.setBlockWhenExhausted(true); // 连接耗尽时，是否阻塞，false会抛出异常，true会阻塞到超时，默认为true

        pool = new JedisPool(config, redisIp, redisPort, 1000*2); // ms单位
    }

    // 为了使类加载到JVM时就初始化连接池，故需要写静态代码块
    static {
        init();
    }
    // 向外部开放获取和返回连接的方法
    // 将jedis实例开放出去
    public static Jedis getJedis() {
        return pool.getResource();
    }
    // 将jedis实例放回连接池
    public static void returnResource(Jedis jedis) {
        pool.returnResource(jedis);  // 此处没有判断是否为空，是因为调用的方法中已经判断了
    }
    // 将损坏的jedis实例放回损坏的连接池
    public static void returnBrokenResource(Jedis jedis) {
        pool.returnBrokenResource(jedis);   // 此处没有判断是否为空，是因为调用的方法中已经判断了
    }

    public static void main(String[] args) {
        Jedis jedis = getJedis();
        jedis.set("hello","redis");
        returnResource(jedis);

        pool.destroy(); // 临时调用，销毁掉所有的连接
        System.out.println("program is end");
    }

}
