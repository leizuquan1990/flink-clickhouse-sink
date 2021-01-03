package ru.ivi.opensource.flinkclickhousesink.applied;

import com.google.common.collect.Lists;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.asynchttpclient.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ivi.opensource.flinkclickhousesink.model.ClickHouseRequestBlank;
import ru.ivi.opensource.flinkclickhousesink.model.ClickHouseSinkCommonParams;
import ru.ivi.opensource.flinkclickhousesink.util.FutureUtil;
import ru.ivi.opensource.flinkclickhousesink.util.ThreadUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// clickhouse 写类
public class ClickHouseWriter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ClickHouseWriter.class);
    private ExecutorService service;
    private ExecutorService callbackService;
    private List<WriterTask> tasks;
    private final BlockingQueue<ClickHouseRequestBlank> commonQueue;
    private final AtomicLong unprocessedRequestsCounter = new AtomicLong(); // 记录未处理任务数量
    private final AsyncHttpClient asyncHttpClient;
    private final List<CompletableFuture<Boolean>> futures;
    private final ClickHouseSinkCommonParams sinkParams;

    public ClickHouseWriter(ClickHouseSinkCommonParams sinkParams, List<CompletableFuture<Boolean>> futures) {
        this(sinkParams, futures, Dsl.asyncHttpClient());
    }

    public ClickHouseWriter(ClickHouseSinkCommonParams sinkParams, List<CompletableFuture<Boolean>> futures, AsyncHttpClient asyncHttpClient) {
        this.sinkParams = sinkParams;
        this.futures = futures;
        this.commonQueue = new LinkedBlockingQueue<>(sinkParams.getQueueMaxCapacity());
        this.asyncHttpClient = asyncHttpClient;
        initDirAndExecutors();
    }

    private void initDirAndExecutors() {
        try {
            // 初始化：失败时记录信息存放目录，如果目录不存在则进行创建
            initDir(sinkParams.getFailedRecordsPath());
            buildComponents();
        } catch (Exception e) {
            logger.error("Error while starting CH writer", e);
            throw new RuntimeException(e);
        }
    }


    private static void initDir(String pathName) throws IOException {
        Path path = Paths.get(pathName);
        Files.createDirectories(path);
    }

    private void buildComponents() {
        logger.info("Building components");

        ThreadFactory threadFactory = ThreadUtil.threadFactory("clickhouse-writer");
        // 根据配置的Writers数量，创建一个固定大小的线程池
        service = Executors.newFixedThreadPool(sinkParams.getNumWriters(), threadFactory);

        // 创建回调线程池
        ThreadFactory callbackServiceFactory = ThreadUtil.threadFactory("clickhouse-writer-callback-executor");

        int cores = Runtime.getRuntime().availableProcessors();
        int coreThreadsNum = Math.max(cores / 4, 2);
        callbackService = new ThreadPoolExecutor(
                coreThreadsNum,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                callbackServiceFactory);


        int numWriters = sinkParams.getNumWriters();
        // 如果容器超过定义size，它会自动扩容，不用担心容量不够。扩容后，会将原来的数组复制到新的数组中，但扩容会带来一定的性能影响：包括开辟新空间，copy数据，耗时，耗性能
        tasks = Lists.newArrayListWithCapacity(numWriters);
        for (int i = 0; i < numWriters; i++) {
            WriterTask task = new WriterTask(i, asyncHttpClient, commonQueue, sinkParams, callbackService, futures, unprocessedRequestsCounter);
            tasks.add(task);
            service.submit(task);
        }
    }

    public void put(ClickHouseRequestBlank params) {
        try {
            unprocessedRequestsCounter.incrementAndGet(); // 未处理任务加1
            commonQueue.put(params);
        } catch (InterruptedException e) {
            logger.error("Interrupted error while putting data to queue", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void waitUntilAllFuturesDone() {
        logger.info("Wait until all futures are done or completed exceptionally. Futures size: {}", futures.size());
        try {
            while (unprocessedRequestsCounter.get() > 0 || !futures.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Futures size: {}.", futures.size());
                }
                CompletableFuture<Void> future = FutureUtil.allOf(futures);
                try {
                    future.get();
                    futures.removeIf(f -> f.isDone() && !f.isCompletedExceptionally());
                    if (logger.isDebugEnabled()) {
                        logger.debug("Futures size after removing: {}", futures.size());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            stopWriters();
            futures.clear();
        }
    }

    private void stopWriters() {
        logger.info("Stopping writers.");
        if (tasks != null && tasks.size() > 0) {
            tasks.forEach(WriterTask::setStopWorking);
        }
        logger.info("Writers stopped.");
    }

    @Override
    public void close() throws Exception {
        logger.info("ClickHouseWriter is shutting down.");
        try {
            waitUntilAllFuturesDone();
        } finally {
            ThreadUtil.shutdownExecutorService(service);
            ThreadUtil.shutdownExecutorService(callbackService);
            asyncHttpClient.close();
            logger.info("{} shutdown complete.", ClickHouseWriter.class.getSimpleName());
        }
    }

    // 写任务线程
    static class WriterTask implements Runnable {
        private static final Logger logger = LoggerFactory.getLogger(WriterTask.class);

        private static final int HTTP_OK = 200;

        private final BlockingQueue<ClickHouseRequestBlank> queue;
        private final AtomicLong queueCounter; // 记录未处理任务数量
        private final ClickHouseSinkCommonParams sinkSettings;
        private final AsyncHttpClient asyncHttpClient;
        private final ExecutorService callbackService;
        private final List<CompletableFuture<Boolean>> futures;

        private final int id;

        private volatile boolean isWorking;

        WriterTask(int id,
                   AsyncHttpClient asyncHttpClient,
                   BlockingQueue<ClickHouseRequestBlank> queue,
                   ClickHouseSinkCommonParams settings,
                   ExecutorService callbackService,
                   List<CompletableFuture<Boolean>> futures,
                   AtomicLong queueCounter) {
            this.id = id;
            this.sinkSettings = settings;
            this.queue = queue;
            this.callbackService = callbackService;
            this.asyncHttpClient = asyncHttpClient;
            this.futures = futures;
            this.queueCounter = queueCounter;
        }

        @Override
        public void run() {
            try {
                isWorking = true;

                logger.info("Start writer task, id = {}", id);
                while (isWorking || queue.size() > 0) {
                    ClickHouseRequestBlank blank = queue.poll(300, TimeUnit.MILLISECONDS);
                    if (blank != null) {
                        CompletableFuture<Boolean> future = new CompletableFuture<>();
                        futures.add(future);
                        send(blank, future);
                    }
                }
            } catch (Exception e) {
                logger.error("Error while inserting data", e);
                throw new RuntimeException(e);
            } finally {
                logger.info("Task id = {} is finished", id);
            }
        }

        private void send(ClickHouseRequestBlank requestBlank, CompletableFuture<Boolean> future) {
            Request request = buildRequest(requestBlank);
            logger.info("Ready to load data to {}, size = {}", requestBlank.getTargetTable(), requestBlank.getValues().size());
            ListenableFuture<Response> whenResponse = asyncHttpClient.executeRequest(request);
            Runnable callback = responseCallback(whenResponse, requestBlank, future);
            whenResponse.addListener(callback, callbackService);
        }

        private Request buildRequest(ClickHouseRequestBlank requestBlank) {
            String resultCSV = String.join(" , ", requestBlank.getValues());
            String query = String.format("INSERT INTO %s VALUES %s", requestBlank.getTargetTable(), resultCSV);
            String host = sinkSettings.getClickHouseClusterSettings().getRandomHostUrl();

            BoundRequestBuilder builder = asyncHttpClient
                    .preparePost(host)
                    .setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
                    .setBody(query);

            if (sinkSettings.getClickHouseClusterSettings().isAuthorizationRequired()) {
                builder.setHeader(HttpHeaderNames.AUTHORIZATION, "Basic " + sinkSettings.getClickHouseClusterSettings().getCredentials());
            }

            return builder.build();
        }

        private Runnable responseCallback(ListenableFuture<Response> whenResponse, ClickHouseRequestBlank requestBlank, CompletableFuture<Boolean> future) {
            return () -> {
                Response response = null;
                try {
                    response = whenResponse.get();

                    if (response.getStatusCode() != HTTP_OK) {
                        handleUnsuccessfulResponse(response, requestBlank, future);
                    } else {
                        logger.info("Successful send data to ClickHouse, batch size = {}, target table = {}, current attempt = {}",
                                requestBlank.getValues().size(),
                                requestBlank.getTargetTable(),
                                requestBlank.getAttemptCounter());
                        future.complete(true);
                    }
                } catch (Exception e) {
                    logger.error("Error while executing callback, params = {}", sinkSettings, e);
                    requestBlank.setException(e);
                    try {
                        handleUnsuccessfulResponse(response, requestBlank, future);
                    } catch (Exception error) {
                        logger.error("Error while handle unsuccessful response", error);
                        future.completeExceptionally(error);
                    }
                } finally {
                    queueCounter.decrementAndGet(); // 将已处理的任务数量减1
                }
            };
        }

        private void handleUnsuccessfulResponse(Response response, ClickHouseRequestBlank requestBlank, CompletableFuture<Boolean> future) throws Exception {
            int currentCounter = requestBlank.getAttemptCounter();
            if (currentCounter >= sinkSettings.getMaxRetries()) {
                logger.warn("Failed to send data to ClickHouse, cause: limit of attempts is exceeded." +
                        " ClickHouse response = {}. Ready to flush data on disk.", response, requestBlank.getException());
                logFailedRecords(requestBlank);
                future.completeExceptionally(new RuntimeException(String.format("Failed to send data to ClickHouse, cause: limit of attempts is exceeded." +
                        " ClickHouse response: %s. Cause: %s", response != null ? response.getResponseBody() : null, requestBlank.getException())));
            } else {
                requestBlank.incrementCounter();
                logger.warn("Next attempt to send data to ClickHouse, table = {}, buffer size = {}, current attempt num = {}, max attempt num = {}, response = {}",
                        requestBlank.getTargetTable(),
                        requestBlank.getValues().size(),
                        requestBlank.getAttemptCounter(),
                        sinkSettings.getMaxRetries(),
                        response);
                queueCounter.incrementAndGet();
                queue.put(requestBlank);
                future.complete(false);
            }
        }

        private void logFailedRecords(ClickHouseRequestBlank requestBlank) throws Exception {
            String filePath = String.format("%s/%s_%s",
                    sinkSettings.getFailedRecordsPath(),
                    requestBlank.getTargetTable(),
                    System.currentTimeMillis());

            try (PrintWriter writer = new PrintWriter(filePath)) {
                List<String> records = requestBlank.getValues();
                records.forEach(writer::println);
                writer.flush();
            }
            logger.info("Successful send data on disk, path = {}, size = {} ", filePath, requestBlank.getValues().size());
        }

        void setStopWorking() {
            isWorking = false;
        }
    }
}