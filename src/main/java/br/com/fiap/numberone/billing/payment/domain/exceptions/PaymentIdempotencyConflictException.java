package br.com.fiap.numberone.billing.payment.domain.exceptions;

public class PaymentIdempotencyConflictException extends RuntimeException {
    public PaymentIdempotencyConflictException() {
        super("Chave de idempotência ou referência comercial reutilizada com dados diferentes");
    }
}
