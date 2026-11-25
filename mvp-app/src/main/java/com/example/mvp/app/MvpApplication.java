package com.example.mvp.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p>{@code scanBasePackages} 指向 {@code com.example.mvp}，
 * 这样 {@code mvp-common} 里的 {@code @Component} / {@code @Configuration}
 * 才能被扫到 —— 对照 customer_work：那边是 starter 用
 * {@code @AutoConfiguration} + {@code AutoConfiguration.imports} 声明，
 * 本 MVP 刻意用最朴素的包扫描，把「Spring 怎么发现 Bean」这层也看清。
 */
@SpringBootApplication(scanBasePackages = "com.example.mvp")
public class MvpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvpApplication.class, args);
    }
}
