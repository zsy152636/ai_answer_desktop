package com.aia.answer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开定时任务：异步任务轮询（开发文档 §8）。
 *
 * <p>本地单机场景下不引入消息队列，任务表即队列；轮询间隔取 3 秒，
 * 既保证体感及时，也不至于在空闲时反复扫表。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
