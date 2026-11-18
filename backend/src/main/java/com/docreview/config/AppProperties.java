package com.docreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置统一从 application.yml 读取，不在代码里重复写默认值，避免两处配置漂移。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Storage storage = new Storage();
    private Agent agent = new Agent();
    private Review review = new Review();
    private Chat chat = new Chat();
    private Auth auth = new Auth();
    private Cors cors = new Cors();
    private Sse sse = new Sse();
    private Executor executor = new Executor();

    @Getter
    @Setter
    public static class Storage {
        /** 原文件落盘目录 */
        private String dir;
        /** 单用户上传总量上限（字节） */
        private long maxUserBytes;
    }

    @Getter
    @Setter
    public static class Agent {
        private String baseUrl;
        private String javaBaseUrl;
        private int timeoutSeconds;
    }

    @Getter
    @Setter
    public static class Review {
        /** 解析及 Python 审查分片的字符上限 */
        private int sectionMaxChars;
        /** 历史审查记录返回条数 */
        private int historyLimit;
    }

    @Getter
    @Setter
    public static class Chat {
        /** 问答请求携带的历史消息条数 */
        private int historyLimit;
    }

    @Getter
    @Setter
    public static class Auth {
        /** HS256 签名密钥；留空时启动随机生成（重启后 token 失效） */
        private String secret;
        /** token 有效期（小时） */
        private int ttlHours;
        /** 生产环境走 HTTPS 时应置为 true */
        private boolean cookieSecure;
        /** SameSite 策略；前后端跨站部署时可设为 None（需同时开启 Secure） */
        private String cookieSameSite;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Sse {
        /** 审查进度 SSE 连接超时（分钟） */
        private int taskTimeoutMinutes;
    }

    @Getter
    @Setter
    public static class Executor {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
    }
}
