package org.dromara.test;

import io.netty.buffer.ByteBuf;
import org.dromara.common.redis.config.RedisConfig;
import org.dromara.common.redis.config.properties.RedissonProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev") @Tag("prod") @Tag("local")
class RedisCodecSecurityTest {
    @Test
    void jsonCacheRoundTripWorksWithoutTheUnusedForyParser() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.fory.Fory"));
        var owner = new RedisConfig();
        var properties = new RedissonProperties();
        properties.setThreads(2);
        properties.setNettyThreads(2);
        ReflectionTestUtils.setField(owner, "redissonProperties", properties);
        var config = new Config();
        owner.redissonCustomizer().customize(config);
        assertInstanceOf(CompositeCodec.class, config.getCodec());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "synthetic-cache-fixture");
        value.put("amount", 42);
        ByteBuf bytes = config.getCodec().getValueEncoder().encode(value);
        try {
            assertEquals(value, config.getCodec().getValueDecoder().decode(bytes, null));
        } finally {
            bytes.release();
        }
    }
    @Test
    void snailJobJsonClientRemainsAvailableWithoutTheBinaryParser() {
        assertNotNull(new com.aizuda.snailjob.client.common.config.SnailJobProperties().getFory());
        Map<String, Object> value = Map.of("task", "synthetic-job", "attempt", 1);
        String json = com.aizuda.snailjob.common.core.util.JsonUtil.toJsonString(value);
        assertEquals(value, com.aizuda.snailjob.common.core.util.JsonUtil.parseObject(json, Map.class));
    }

}
