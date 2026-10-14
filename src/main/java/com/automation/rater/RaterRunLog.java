package com.automation.rater;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Copies everything logged during a run into a text file (the "console log") next to the run's other outputs.
 * Logging is limited to INFO for the run so the file stays readable and never contains request bodies or tokens.
 */
public final class RaterRunLog implements AutoCloseable {
    private final Logger root;
    private final FileAppender<ILoggingEvent> appender;
    private final Level previousLevel;
    private final Logger app;
    private final boolean attachedToApp;

    private RaterRunLog(Logger root, Logger app, boolean attachedToApp, FileAppender<ILoggingEvent> appender, Level previousLevel) {
        this.root = root;
        this.app = app;
        this.attachedToApp = attachedToApp;
        this.appender = appender;
        this.previousLevel = previousLevel;
    }

    public static RaterRunLog start(Path file) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return new RaterRunLog(null, null, false, null, null);       // not logback: nothing to attach
        }
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{30} - %msg%n");
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setName("RATER_RUN_LOG");
        appender.setFile(file.toAbsolutePath().toString());
        appender.setAppend(false);
        appender.setEncoder(encoder);
        appender.start();

        // Keep the file at INFO even when a logger (e.g. com.automation) is set to DEBUG, so no bodies/tokens are written
        ThresholdFilter info = new ThresholdFilter();
        info.setLevel("INFO");
        info.setContext(context);
        info.start();
        appender.addFilter(info);

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.addAppender(appender);
        root.setLevel(Level.INFO);

        // logback.xml may set additivity=false on com.automation; then its events never reach root, so attach here too
        Logger app = context.getLogger("com.automation");
        boolean attachedToApp = !app.isAdditive();
        if (attachedToApp) app.addAppender(appender);
        return new RaterRunLog(root, app, attachedToApp, appender, previous);
    }

    @Override
    public void close() {
        if (root == null) return;
        root.detachAppender(appender);
        if (attachedToApp) app.detachAppender(appender);
        appender.stop();
        root.setLevel(previousLevel);
    }
}
