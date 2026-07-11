package com.xiongda;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 熊答后端应用入口。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@SpringBootApplication
@MapperScan("com.xiongda.mapper")
@EnableAsync
public class MainApplication {

    public static void main(String[] args) {
        SpringApplication.run(MainApplication.class, args);
    }
}
