package com.wdh.jjkk_2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 后端服务启动入口。
 *
 * 这里开启 Spring 定时任务能力，因为项目里的行情刷新、基金收盘真实净值校准、
 * Redis 当日分时数据落库、资讯过期清理等任务都不是由用户请求直接触发，
 * 而是依赖后台调度持续运行。后续新增需要按时间执行的任务时，也应统一放到
 * scheduler/service 中实现，由这个启动类提供调度开关。
 */
@EnableScheduling
@SpringBootApplication
public class Jjkk2Application {

    public static void main(String[] args) {
        SpringApplication.run(Jjkk2Application.class, args);
    }

}

