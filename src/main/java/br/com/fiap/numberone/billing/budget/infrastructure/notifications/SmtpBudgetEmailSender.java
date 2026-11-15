package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class SmtpBudgetEmailSender {
    private final JavaMailSender sender;
    private final String from;

    public SmtpBudgetEmailSender(JavaMailSender sender, String from) {
        if (from == null || from.isBlank() || from.contains("\r") || from.contains("\n")) {
            throw new IllegalArgumentException("O remetente SMTP é obrigatório e deve ser válido");
        }
        this.sender = sender;
        this.from = from;
    }

    public void send(BudgetApprovalEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.recipient());
        message.setSubject("Orçamento disponível para aprovação");
        message.setText("""
                Olá!

                O orçamento %s está disponível para sua análise.
                Valor proposto: %s

                Acesse o link para consultar o orçamento e registrar sua decisão:
                %s

                Este link é válido até %s (UTC). Se ele expirar, solicite um novo envio à equipe.
                A abertura do link não aprova nem rejeita o orçamento.
                """.formatted(email.budgetId(), email.quotedAmount().toPlainString(), email.approvalUrl(),
                DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss").withZone(ZoneOffset.UTC).format(email.expiresAt())));
        sender.send(message);
    }
}
