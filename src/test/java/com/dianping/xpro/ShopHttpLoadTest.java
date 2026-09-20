package com.dianping.xpro;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商铺详情 HTTP 端到端压测。只有显式传入 {@code shop.load.url} 时才执行，避免普通单测
 * 意外依赖外部应用。延迟从客户端发起请求前开始，到状态码和完整响应体接收完毕为止。
 */
class ShopHttpLoadTest {

    @Test
    void measureShopDetailHttpQpsAndLatencyPercentiles() throws Exception {
        String url = System.getProperty("shop.load.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "设置 -Dshop.load.url=http://127.0.0.1:8081/shop/1 后执行 HTTP 压测");

        int concurrency = positiveIntProperty("shop.load.concurrency", 64);
        int totalRequests = positiveIntProperty("shop.load.requests", 100_000);
        int warmUpRequests = positiveIntProperty("shop.load.warmup", 2_000);
        int timeoutSeconds = positiveIntProperty("shop.load.timeout-seconds", 180);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        for (int i = 0; i < warmUpRequests; i++) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessful(response);
        }

        long[] latenciesNanos = new long[totalRequests];
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>(concurrency);
        long loadStartedAt;

        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            int requestsPerWorker = totalRequests / concurrency;
            int remainder = totalRequests % concurrency;
            for (int worker = 0; worker < concurrency; worker++) {
                int workerRequests = requestsPerWorker + (worker < remainder ? 1 : 0);
                int latencyOffset = worker * requestsPerWorker + Math.min(worker, remainder);
                workers.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < workerRequests; i++) {
                        long requestStartedAt = System.nanoTime();
                        try {
                            HttpResponse<String> response =
                                    client.send(request, HttpResponse.BodyHandlers.ofString());
                            if (!isSuccessful(response)) {
                                errors.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            latenciesNanos[latencyOffset + i] = System.nanoTime() - requestStartedAt;
                        }
                    }
                    return null;
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            loadStartedAt = System.nanoTime();
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        }

        long elapsedNanos = System.nanoTime() - loadStartedAt;
        Arrays.sort(latenciesNanos);
        double qps = totalRequests * 1_000_000_000.0 / elapsedNanos;
        double p50Millis = percentileNanos(latenciesNanos, 0.50) / 1_000_000.0;
        double p95Millis = percentileNanos(latenciesNanos, 0.95) / 1_000_000.0;
        double p99Millis = percentileNanos(latenciesNanos, 0.99) / 1_000_000.0;
        double maxMillis = latenciesNanos[latenciesNanos.length - 1] / 1_000_000.0;

        System.out.printf(Locale.ROOT,
                "SHOP_HTTP_LOAD url=%s requests=%d concurrency=%d elapsedSec=%.3f "
                        + "qps=%.2f p50Ms=%.3f p95Ms=%.3f p99Ms=%.3f maxMs=%.3f errors=%d%n",
                url, totalRequests, concurrency, elapsedNanos / 1_000_000_000.0,
                qps, p50Millis, p95Millis, p99Millis, maxMillis, errors.get());

        assertThat(errors.get()).as("HTTP 非 200、业务失败或请求异常数").isZero();
    }

    private static boolean isSuccessful(HttpResponse<String> response) {
        return response.statusCode() == 200 && response.body().contains("\"success\":true");
    }

    private static void assertSuccessful(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"success\":true");
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

    private static long percentileNanos(long[] sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.length) - 1;
        return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
    }
}
