package com.omnixys.person.messaging;

import com.omnixys.person.config.AppProperties;
import com.omnixys.person.models.events.CreateAccountDTO;
import com.omnixys.person.models.events.DeleteAccountDTO;
import com.omnixys.person.models.events.LogDTO;
import com.omnixys.person.models.events.SendMailEvent;
import com.omnixys.person.models.events.ShoppingCartDTO;
import com.omnixys.person.models.entities.Person;
import com.omnixys.person.tracing.TraceContextUtil;
import io.micrometer.observation.annotation.Observed;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.omnixys.person.messaging.KafkaTopicProperties.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaPublisherService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Tracer tracer;
    private final KafkaUtilService kafkaUtilService;
    private final AppProperties appProperties;

    /**
     * Versendet ein Logging-Event an das zentrale Logging-System via Kafka.
     *
     * @param level   z.B. INFO, WARN, DEBUG, ERROR
     * @param message Die zu loggende Nachricht
     * @param context Kontext wie Klassen- oder Methodenname
     */
    @Observed(name = "kafka-publisher.log")
    public void log(String level, String message, String serviceName, String context) {
        SpanContext spanContext = Span.current().getSpanContext();

        final var event = new LogDTO(
            UUID.randomUUID(),
            Instant.now(),
            level,
            message,
            serviceName,
            context,
            spanContext.isValid() ? spanContext.getTraceId() : null,
            spanContext.isValid() ? spanContext.getSpanId() : null,
            TraceContextUtil.getUsernameOrNull(), // eigene Util-Methode
            appProperties.getEnv() // ← z.B. „dev“, „prod“
        );

        sendKafkaEvent(TOPIC_LOG_STREAM_LOG_PERSON, event, "log");
    }

    @Observed(name = "kafka-publisher.send-mail")
    public void sendMail(String topic, Person person, String role, boolean isDeleted) {
        final var mailDTO = isDeleted
            ? SendMailEvent.toDeleteEvent(person)
            : SendMailEvent.toCreateEvent(person, role);

        sendKafkaEvent(topic, mailDTO, "sendMail");
    }


    @Observed(name = "kafka-publisher.create-account")
    public void createAccount(UUID personId, String username) {
        final var accountDTO = new CreateAccountDTO(BigDecimal.ZERO, "CHECKING", 2, 50, 20, personId, 500, username);
        sendKafkaEvent(TOPIC_ACCOUNT_CREATE_PERSON, accountDTO, "createAccount");
    }

    @Observed(name = "kafka-publisher.delete-account")
    public void deleteAccount(UUID personId, int version, String username) {
        final var deleteAccountDTO = new DeleteAccountDTO(personId, version, username);
        sendKafkaEvent(TOPIC_ACCOUNT_DELETE_PERSON, deleteAccountDTO, "deleteAccount");
    }

    @Observed(name = "kafka-publisher.shopping-cart")
    public void createShoppingCart(UUID personId, String username) {
        final var shoppingCartDTO = new ShoppingCartDTO(personId, username, "");
        sendKafkaEvent(TOPIC_SHOPPING_CART_CREATE_PERSON, shoppingCartDTO, "createShoppingCart");
    }

    @Observed(name = "kafka-publisher.shopping-cart")
    public void deleteShoppingCart(UUID personId) {
        sendKafkaEvent(TOPIC_SHOPPING_CART_DELETE_PERSON, personId.toString(), "deleteShoppingCart");
    }

    /**
     * Zentraler Kafka-Versand mit OpenTelemetry-Span.
     *
     * @param topic     Ziel-Topic
     * @param payload   Event-Inhalt (DTO oder String)
     * @param operation Name der Aktion, z.B. 'createAccount'
     */
    private void sendKafkaEvent(String topic, Object payload, String operation) {
        Span kafkaSpan = tracer.spanBuilder(String.format("kafka-publisher.%s", topic))
            .setParent(Context.current())
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination", topic)
            .setAttribute("messaging.destination_kind", "topic")
            .setAttribute("messaging.operation", operation)
            .startSpan();
        try (Scope scope = kafkaSpan.makeCurrent()) {
            assert scope != null;
            SpanContext spanContext = kafkaSpan.getSpanContext();
            //SpanContext spanContext = Span.current().getSpanContext();

            Headers headers = kafkaUtilService.buildStandardHeaders(topic, operation, spanContext);

//            headers.add(new RecordHeader("__TypeId__", operation.getBytes(StandardCharsets.UTF_8)));
//            headers.forEach(header ->
//                log.info("HEADER: {} = {}", header.key(), new String(header.value(), StandardCharsets.UTF_8))
//            );

            ProducerRecord<String, Object> record = new ProducerRecord<>(topic, null, null, null, payload, headers);
            kafkaTemplate.send(record);

            kafkaSpan.setAttribute("messaging.kafka.message_type", payload.getClass().getSimpleName());
        } catch (Exception e) {
            kafkaSpan.recordException(e);
            kafkaSpan.setStatus(StatusCode.ERROR, "Kafka send failed");
            log.error("❌ Kafka send failed: topic={}, payload={}", topic, payload, e);
        } finally {
            kafkaSpan.end();
        }

        log.info("📤 Kafka-Event '{}' an Topic '{}': {}", operation, topic, payload);
    }
}
