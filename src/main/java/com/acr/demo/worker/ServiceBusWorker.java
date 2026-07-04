package com.acr.demo.worker;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Service Bus background worker that listens to a queue and processes messages.
 *
 * Configuration is read from environment variables:
 *  - SERVICE_BUS_CONNECTION_STRING (required to start)
 *  - SERVICE_BUS_QUEUE (defaults to "orders")
 */
@Component
public class ServiceBusWorker {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusWorker.class);

    private ServiceBusProcessorClient processor;

    @PostConstruct
    public void start() {
        String conn = System.getenv("SERVICE_BUS_CONNECTION_STRING");
        String queue = System.getenv("SERVICE_BUS_QUEUE");
        if (queue == null || queue.isEmpty()) {
            queue = "orders";
        }

        if (conn == null || conn.isEmpty()) {
            log.warn("SERVICE_BUS_CONNECTION_STRING not set — ServiceBusWorker will not start.");
            return;
        }

        processor = new ServiceBusClientBuilder()
                .connectionString(conn)
                .processor()
                .queueName(queue)
                .processMessage(context -> {
                    String body = context.getMessage().getBody().toString();
                    log.info("Processing message: {}", body);
                    // simulate work
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .processError(errorContext -> {
                    log.error("Error while processing messages: {}", errorContext.getException().getMessage(), errorContext.getException());
                })
                .buildProcessorClient();

        processor.start();
        log.info("ServiceBus processor started for queue '{}'.", queue);
    }

    @PreDestroy
    public void stop() {
        if (processor != null) {
            processor.close();
            log.info("ServiceBus processor stopped.");
        }
    }
}

