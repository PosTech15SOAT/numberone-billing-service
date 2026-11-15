package br.com.fiap.numberone.billing.infrastructure.observability.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Injeta falhas que escapam do backend, sem a absorção de exceções de AppenderBase.doAppend. */
public final class FailingLogAppender extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final RuntimeException failure = new IllegalStateException("Logging indisponível");
    private final List<ILoggingEvent> attempts = new ArrayList<>();

    public FailingLogAppender(Class<?> source) {
        logger = (Logger) LoggerFactory.getLogger(source);
        previousLevel = logger.getLevel();
        setContext(logger.getLoggerContext());
        start();
        logger.setLevel(Level.TRACE);
        logger.addAppender(this);
    }

    @Override
    public void doAppend(ILoggingEvent event) {
        append(event);
    }

    @Override
    protected void append(ILoggingEvent event) {
        attempts.add(event);
        throw failure;
    }

    public List<String> eventCodes() {
        return attempts.stream().flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> pair.key.equals("event.code")).map(pair -> pair.value.toString()).toList();
    }

    public RuntimeException failure() { return failure; }

    @Override
    public void close() {
        logger.detachAppender(this);
        logger.setLevel(previousLevel);
        stop();
    }
}
