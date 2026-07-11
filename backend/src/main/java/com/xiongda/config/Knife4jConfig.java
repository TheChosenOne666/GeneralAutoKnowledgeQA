package com.xiongda.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 文档配置。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("熊答 API 文档")
                        .description("基于 RAG + Agent 的多租户智能问答平台")
                        .version("0.1.0"));
    }
}
