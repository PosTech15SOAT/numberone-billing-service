package br.com.fiap.numberone.billing.infrastructure.observability;

/** Uma falha do destino de logs não pode alterar o resultado comercial. */
public final class ObservationLog {
    private ObservationLog() { }

    public static void safely(Runnable logging) {
        try { logging.run(); }
        catch (RuntimeException ignored) { /* Observabilidade não substitui o resultado da operação. */ }
    }
}
