package br.com.fiap.numberone.billing.budget.infrastructure.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Diagnóstico técnico sem mensagens, valores SQL ou respostas de provedores. */
public final class SafeExceptionDetails {

    private SafeExceptionDetails() {
    }

    public static String stackTrace(Throwable failure) {
        var trace = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; failure != null && depth < 8 && visited.add(failure); depth++, failure = failure.getCause()) {
            if (depth > 0) trace.append("Caused by: ");
            trace.append(failure.getClass().getName()).append('\n');
            var frames = failure.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 20); index++) {
                trace.append("\tat ").append(frames[index]).append('\n');
            }
            if (frames.length > 20) trace.append("\t... frames omitidos\n");
        }
        return trace.toString();
    }
}
