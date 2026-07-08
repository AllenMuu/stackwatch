package com.stackwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * StackWatch 启动类。
 * 扫描 com.stackwatch 及子包：domain / preprocess / analyzer / config / web。
 */
@SpringBootApplication
public class StackWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(StackWatchApplication.class, args);
    }
}
