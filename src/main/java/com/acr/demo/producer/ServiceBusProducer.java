package com.acr.demo.producer;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone producer you can run locally to flood the Service Bus queue for testing scaling.
 *
 * Usage examples:
 *   # send 100 messages (default) using env var
 *   SERVICE_BUS_CONNECTION_STRING="..." mvn -Dexec.mainClass=com.acr.demo.producer.ServiceBusProducer exec:java
 *
 *   # or run with the packaged jar/main class directly via mvn exec or IDE
 */
public class ServiceBusProducer {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusProducer.class);

    public static void main(String[] args) {
        String conn = System.getenv("SERVICE_BUS_CONNECTION_STRING");
        if (conn == null || conn.isEmpty()) {
            System.err.println("Please set SERVICE_BUS_CONNECTION_STRING environment variable and re-run.");
            System.exit(1);
        }

        String queue = System.getenv("SERVICE_BUS_QUEUE");
        if (queue == null || queue.isEmpty()) {
            queue = "orders";
        }

        int count = 100;
        if (args.length > 0) {
            try { count = Integer.parseInt(args[0]); } catch (Exception e) { /* ignore */ }
        }

        log.info("Sending {} messages to queue '{}'...", count, queue);

        ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(conn)
                .sender()
                .queueName(queue)
                .buildClient();

        try {
            for (int i = 1; i <= count; i++) {
                String body = "Message " + i;
                ServiceBusMessage message = new ServiceBusMessage(body);
                sender.sendMessage(message);
                if (i % 10 == 0) {
                    log.info("Sent {} messages", i);
                }
                try { Thread.sleep(10); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            log.info("All messages sent.");
        } finally {
            sender.close();
        }
    }
}

