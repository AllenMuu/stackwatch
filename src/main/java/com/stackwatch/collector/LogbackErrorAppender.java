package com.stackwatch.collector;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Logback Appender：①采集层 Logback 集成。
 *
 * 自动捕获应用日志中带异常的 ERROR 事件 -> 从 {@link IThrowableProxy} 提取
 * 异常类型/消息/堆栈 -> 委托 {@link ErrorEventCollector} 触发根因分析。
 *
 * 集成难点：Logback 通过反射实例化 Appender，无法 @Autowired Spring bean。
 * 解法：内部静态类 {@link SpringContextHolder} 实现 {@link ApplicationContextAware}，
 * 由 Spring 扫描注册后静态持有 ApplicationContext；append 时按需取 bean。
 *
 * 线程安全：
 * - {@code context} 字段 volatile，保证多核可见性；
 * - {@link AppenderBase#doAppend} 已对 append 同步（逐条串行），append 本身无需再加锁；
 * - {@link #RECURSION_GUARD} ThreadLocal 防止分析链路自身日志回流触发递归采集。
 *
 * 已知限制（MVP）：collect 同步执行，L3 缓存未命中时会阻塞业务线程等 LLM 响应。
 * 生产化建议：将 collect 改为投递到有界队列异步消费（见 notes）。
 */
public class LogbackErrorAppender extends AppenderBase<ILoggingEvent> {

    /** 递归保护：分析链路内部若触发 ERROR 日志，跳过本 appender 避免无限递归。 */
    private static final ThreadLocal<Boolean> RECURSION_GUARD =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Spring 上下文静态持有者。
     * 作为嵌套 @Component 由 Spring 扫描注册，{@link ApplicationContextAware}
     * 回调把上下文写入 static 字段，供 Logback 反射创建的 Appender 实例访问 bean。
     */
    @Component
    public static class SpringContextHolder implements ApplicationContextAware {

        private static volatile ApplicationContext context;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            context = applicationContext;
        }

        /** 取 ErrorEventCollector bean；上下文未就绪或 bean 缺失时返回 null。 */
        static ErrorEventCollector collector() {
            ApplicationContext ctx = context;
            if (ctx == null) {
                return null;
            }
            try {
                return ctx.getBean(ErrorEventCollector.class);
            } catch (BeansException e) {
                // bean 尚未初始化或上下文关闭中：返回 null 让调用方跳过
                return null;
            }
        }

        static String appName() {
            ApplicationContext ctx = context;
            if (ctx == null) {
                return "stackwatch";
            }
            return ctx.getEnvironment().getProperty("spring.application.name", "stackwatch");
        }

        static String env() {
            ApplicationContext ctx = context;
            if (ctx == null) {
                return "default";
            }
            Environment environment = ctx.getEnvironment();
            String explicit = environment.getProperty("stackwatch.env");
            if (explicit != null && !explicit.isBlank()) {
                return explicit;
            }
            return environment.getProperty("spring.profiles.active", "default");
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (RECURSION_GUARD.get()) {
            return;
        }
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy == null) {
            return; // 无异常的日志不采集
        }

        ErrorEventCollector collector = SpringContextHolder.collector();
        if (collector == null) {
            // Spring 上下文未就绪（启动早期/关闭后）：静默跳过，避免阻塞日志链路
            return;
        }

        List<String> stackTrace = extractStackTrace(proxy);
        Map<String, String> mdc = extractMdc(event);
        String appName = SpringContextHolder.appName();
        String env = SpringContextHolder.env();

        RECURSION_GUARD.set(Boolean.TRUE);
        try {
            collector.collect(
                appName, env,
                proxy.getClassName(), proxy.getMessage(),
                stackTrace, mdc
            );
        } catch (Exception e) {
            // 采集失败不得影响业务日志链路：写 stderr 后吞掉（用 stderr 避免经 SLF4J 自递归）
            System.err.println("[stackwatch] collect failed: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        } finally {
            RECURSION_GUARD.remove();
        }
    }

    private static List<String> extractStackTrace(IThrowableProxy proxy) {
        StackTraceElementProxy[] proxies = proxy.getStackTraceElementProxyArray();
        if (proxies == null || proxies.length == 0) {
            return List.of();
        }
        return Arrays.stream(proxies)
            .map(StackTraceElementProxy::getStackTraceElement)
            .map(Object::toString)
            .toList();
    }

    private static Map<String, String> extractMdc(ILoggingEvent event) {
        Map<String, String> source = event.getMDCPropertyMap();
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(source);
    }
}
