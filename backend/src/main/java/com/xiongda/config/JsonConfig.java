package com.xiongda.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.jackson.JsonComponent;
import org.springframework.context.annotation.Bean;

import java.math.BigInteger;

/**
 * Jackson JSON 配置 — Long 精度问题（前端 JS 无法精确处理超过 16 位的 Long）。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@JsonComponent
public class JsonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        // Long 和 BigInteger 序列化为字符串，防止前端精度丢失
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        module.addSerializer(BigInteger.class, ToStringSerializer.instance);
        objectMapper.registerModule(module);
        return objectMapper;
    }
}
