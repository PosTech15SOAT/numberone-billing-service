package br.com.fiap.numberone.billing.budget.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

/** Captura eventos reais e os serializa com o encoder e a configuração usados pela aplicação. */
public final class EcsLogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private final LoggerContext encoderContext = new LoggerContext();
    private final StructuredLogEncoder encoder = new StructuredLogEncoder();
    private final JsonMapper mapper = new JsonMapper();

    public EcsLogCapture(Class<?> source) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        encoderContext.putObject(Environment.class.getName(), environment);
        encoder.setContext(encoderContext);
        encoder.setFormat(environment.getRequiredProperty("logging.structured.format.console"));
        encoder.start();
        logger = (Logger) LoggerFactory.getLogger(source);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    public List<JsonNode> events() {
        return appender.list.stream().map(event -> mapper.readTree(encoder.encode(event))).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        encoder.stop();
        encoderContext.stop();
    }
}
