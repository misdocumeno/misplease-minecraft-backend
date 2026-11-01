package io.github.misdocumeno.betterwarps;

import redis.clients.jedis.Jedis;

public class RedisManager {

    private final Jedis jedis;

    public RedisManager(String url) {
        this.jedis = new Jedis(url);
    }

    public void publish(String channel, String message) {
        jedis.publish(channel, message);
    }

    public void close() {
        jedis.close();
    }
}
