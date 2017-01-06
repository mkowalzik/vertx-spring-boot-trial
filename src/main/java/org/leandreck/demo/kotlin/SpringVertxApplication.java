package org.leandreck.demo.kotlin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hazelcast.config.Config;
import io.netty.util.CharsetUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.UUID;

@SpringBootApplication
public class SpringVertxApplication {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    VertxOptions vertxOptions;

    @Autowired
    RequestMessageCodec requestMessageCodec;

    @Autowired
    MyFirstVerticle myFirstVerticle;

    @Autowired
    TimerVerticle timerVerticle;

    public static void main(String[] args) {
        SpringApplication.run(SpringVertxApplication.class, args);
    }

    @PostConstruct
    public void deployVerticle() {
        log.info("Starting Clustermanager...");
        Vertx.clusteredVertx(vertxOptions, res -> {
            if (res.succeeded()) {
                log.info("Cluster started");
                Vertx vertx = res.result();
                EventBus eventBus = vertx.eventBus();
                log.info("We now have a clustered event bus: " + eventBus);
                eventBus.registerCodec(requestMessageCodec);

                vertx.deployVerticle(myFirstVerticle);
                vertx.deployVerticle(timerVerticle);
            } else {
                // failed!
                log.error("Clusterstartup failed {}", res.cause());
            }

        });
    }
}

@Configuration
class ApplicationConfig {

    @Bean
    public VertxOptions vertxOptions() {
        final Config hazelcastConfig = new Config();
        hazelcastConfig.setInstanceName("King Bob Cluster");
        final HazelcastClusterManager hazelcastClusterManager = new HazelcastClusterManager(hazelcastConfig);
        return new VertxOptions().setClusterManager(hazelcastClusterManager);
    }

}

@JsonIgnoreProperties(ignoreUnknown = true)
final class RequestMessage {

    private final String msgId;
    private final String host;
    private final int port;
    private final String uri;

    @JsonCreator
    public RequestMessage(
            @JsonProperty("msgId") String msgId,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("uri") String uri) {

        this.msgId = msgId;
        this.host = host;
        this.port = port;
        this.uri = uri;
    }


    public String getMsgId() {
        return msgId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUri() {
        return uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RequestMessage that = (RequestMessage) o;

        if (port != that.port) return false;
        if (msgId != null ? !msgId.equals(that.msgId) : that.msgId != null) return false;
        if (host != null ? !host.equals(that.host) : that.host != null) return false;
        return uri != null ? uri.equals(that.uri) : that.uri == null;
    }

    @Override
    public int hashCode() {
        int result = msgId != null ? msgId.hashCode() : 0;
        result = 31 * result + (host != null ? host.hashCode() : 0);
        result = 31 * result + port;
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "RequestMessage{" +
                "msgId='" + msgId + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", uri='" + uri + '\'' +
                '}';
    }
}

@Component
class RequestMessageCodec implements MessageCodec<RequestMessage, RequestMessage> {

    @Override
    public String name() {
        return "RequestMessageCodec";
    }

    @Override
    public byte systemCodecID() {
        return -1;
    }

    @Override
    public RequestMessage transform(RequestMessage requestMessage) {
        return new RequestMessage(requestMessage.getMsgId(), requestMessage.getHost(), requestMessage.getPort(), requestMessage.getUri());
    }

    @Override
    public void encodeToWire(Buffer buffer, RequestMessage requestMessage) {
        byte[] strBytes = Json.encode(requestMessage).getBytes(CharsetUtil.UTF_8);
        buffer.appendInt(strBytes.length);
        buffer.appendBytes(strBytes);
    }

    @Override
    public RequestMessage decodeFromWire(int pos, Buffer buffer) {
        int length = buffer.getInt(pos);
        int startPayload = pos + 4;
        byte[] bytes = buffer.getBytes(startPayload, startPayload + length);
        return Json.decodeValue(new String(bytes, CharsetUtil.UTF_8), RequestMessage.class);
    }
}

@Component
class MyFirstVerticle extends AbstractVerticle {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Override
    public void start(Future<Void> future) {
        EventBus eventBus = vertx.eventBus();
        eventBus.consumer("send.request", it -> receiveRequest(it));
    }


    public void receiveRequest(Message<?> message) {
        log.info("I have received a message: {}", message.body());

        Object theRequest = message.body();
        if (theRequest instanceof RequestMessage) {
            RequestMessage msg = (RequestMessage) theRequest;
            long start = System.currentTimeMillis();
            log.info("Start={}", start);
            vertx.createHttpClient()
                    .getNow(msg.getPort(), msg.getHost(), msg.getUri(), response -> {
                        log.info("Received response with status code {} and took {}ms", response.statusCode(), System.currentTimeMillis() - start);
                    });
        }
    }

}

@Component
class TimerVerticle extends AbstractVerticle {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Override
    public void start(Future<Void> future) {
        vertx.setPeriodic(10000, it -> {
            sendMessage();
        });
    }

    public void sendMessage() {
        EventBus eventBus = vertx.eventBus();
        RequestMessage requestMessage = new RequestMessage("Java8-"+ UUID.randomUUID(), "www.golem.de", 80, "/");
        log.info(requestMessage.toString());
        eventBus.send("send.request", requestMessage, new DeliveryOptions().setCodecName("RequestMessageCodec"));
    }
}