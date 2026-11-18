package com.docreview;

import com.docreview.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class DocReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocReviewApplication.class, args);
    }
}
