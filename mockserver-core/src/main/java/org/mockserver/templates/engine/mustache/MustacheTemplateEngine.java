package org.mockserver.templates.engine.mustache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.jayway.jsonpath.JsonPath;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.Base64Converter;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.mockserver.uuid.UUIDService;
import org.mockserver.xml.XPathEvaluator;
import org.slf4j.event.Level;

import javax.xml.xpath.XPathConstants;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * See: https://github.com/samskivert/jmustache for syntax
 *
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class MustacheTemplateEngine implements TemplateEngine {

    private static ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;

    public MustacheTemplateEngine(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        if (objectMapper == null) {
            objectMapper = ObjectMapperFactory.createObjectMapper();
        }
    }

    public static class ToString {
        private static final Random random = new Random();
        private static final Base64Converter base64Converter = new Base64Converter();
        private final Supplier<String> supplier;

        public ToString(Supplier<String> supplier) {
            this.supplier = supplier;
        }

        @Override
        public String toString() {
            return supplier.get();
        }

        public static String randomInteger(int max) {
            return String.valueOf(random.nextInt(max));
        }

        public static String randomBytes(int size) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            return String.valueOf(base64Converter.bytesToBase64String(bytes));
        }
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        T result;
        try {
            Writer writer = new StringWriter();
            Template compiledTemplate = Mustache.compiler().compile(template);

            Map<String, Object> data = ImmutableMap.<String, Object>builder()
                .put("request", new HttpRequestTemplateObject(request))
                .put("now", new ToString(() -> DateTimeFormatter.ISO_INSTANT.format(Instant.now())))
                .put("now_epoch", new ToString(() -> String.valueOf(Instant.now().getEpochSecond())))
                .put("now_iso-8601", new ToString(() -> DateTimeFormatter.ISO_INSTANT.format(Instant.now())))
                .put("now_rfc_1123", new ToString(() -> DateTimeFormatter.RFC_1123_DATE_TIME.format(OffsetDateTime.now())))
                .put("uuid", new ToString(UUIDService::getUUID))
                .put("rand_int", new ToString(() -> ToString.randomInteger(10)))
                .put("rand_int_10", new ToString(() -> ToString.randomInteger(10)))
                .put("rand_int_100", new ToString(() -> ToString.randomInteger(100)))
                .put("rand_bytes", new ToString(() -> ToString.randomBytes(16)))
                .put("rand_bytes_16", new ToString(() -> ToString.randomBytes(16)))
                .put("rand_bytes_32", new ToString(() -> ToString.randomBytes(32)))
                .put("rand_bytes_64", new ToString(() -> ToString.randomBytes(64)))
                .put("rand_bytes_128", new ToString(() -> ToString.randomBytes(128)))
                .put("xPath", (Mustache.Lambda) (frag, out) -> evaluatedXPath(frag.execute(), request, out))
                .put("jsonPath", (Mustache.Lambda) (frag, out) -> evaluateJsonPath(frag.execute(), request, out))
                .build();
            compiledTemplate.execute(data, writer);
            JsonNode generatedObject = null;
            try {
                generatedObject = objectMapper.readTree(writer.toString());
            } catch (Throwable throwable) {
                if (MockServerLogger.isEnabled(Level.TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.TRACE)
                            .setHttpRequest(request)
                            .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                            .setArguments(writer.toString(), request)
                    );
                }
            }
            if (MockServerLogger.isEnabled(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(TEMPLATE_GENERATED)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                        .setArguments(generatedObject != null ? generatedObject : writer.toString(), template, request)
                );
            }
            result = httpTemplateOutputDeserializer.deserializer(request, writer.toString(), dtoClass);
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", e.getMessage(), template, request), e);
        }
        return result;
    }

    private void evaluateJsonPath(String jsonPath, HttpRequest request, Writer out) {
        try {
            String jsonPathResult = JsonPath.compile(jsonPath).read(request.getBodyAsJsonOrXmlString()).toString();
            if (MockServerLogger.isEnabled(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setHttpRequest(request)
                        .setMessageFormat("evaluated jsonPath:{}against json body:{}as:{}")
                        .setArguments(jsonPath, request.getBodyAsJsonOrXmlString(), jsonPathResult)
                );
            }
            out.write(jsonPathResult);
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("exception evaluating jsonPath:{}against json body:{}")
                    .setArguments(jsonPath, request.getBodyAsJsonOrXmlString())
                    .setThrowable(throwable)
            );
        }
    }

    private void evaluatedXPath(String xPath, HttpRequest request, Writer out) {
        try {
            String xPathResult = String.valueOf(new XPathEvaluator(xPath, null).evaluateXPathExpression(request.getBodyAsJsonOrXmlString(), (matched, exception, level) -> {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("exception evaluating xPath:{}against xml body:{}")
                        .setArguments(xPath, request.getBodyAsJsonOrXmlString())
                        .setThrowable(exception)
                );
            }, XPathConstants.STRING));
            if (MockServerLogger.isEnabled(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setHttpRequest(request)
                        .setMessageFormat("evaluated xPath:{}against xml body:{}as:{}")
                        .setArguments(xPath, request.getBodyAsJsonOrXmlString(), xPathResult)
                );
            }
            out.write(xPathResult);
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("exception evaluating xPath:{}against xml body:{}")
                    .setArguments(xPath, request.getBodyAsJsonOrXmlString())
                    .setThrowable(throwable)
            );
        }
    }
}