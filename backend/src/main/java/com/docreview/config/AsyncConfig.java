package com.docreview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /** 审查任务的主执行器，线程池参数由 app.executor.* 配置。 */
    @Bean("reviewExecutor")
    public ThreadPoolTaskExecutor reviewExecutor(AppProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("review-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

}
