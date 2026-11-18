package com.docreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Storage storage = new Storage();
    private Agent agent = new Agent();
    private Review review = new Review();
    private Auth auth = new Auth();

    @Getter
    @Setter
    public static class Storage {
        /** 原文件落盘目录 */
        private String dir = "./data/files";
    }

    @Getter
    @Setter
    public static class Agent {
        private String baseUrl = "http://127.0.0.1:8000";
        private String javaBaseUrl = "http://127.0.0.1:8080";
        private int timeoutSeconds = 180;
    }

    @Getter
    @Setter
    public static class Review {
        /** 解析及 Python 审查分片的字符上限 */
        private int sectionMaxChars = 1500;
    }

    @Getter
    @Setter
    public static class Auth {
        /** HS256 签名密钥，生产环境必须通过 APP_AUTH_SECRET 覆盖 */
        private String secret = "docreview-demo-secret-please-change-in-production";
        /** token 有效期（小时） */
        private int ttlHours = 12;
    }
}
