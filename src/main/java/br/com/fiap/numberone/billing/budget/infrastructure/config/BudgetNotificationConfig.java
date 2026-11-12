package br.com.fiap.numberone.billing.budget.infrastructure.config;

import br.com.fiap.numberone.billing.budget.infrastructure.notifications.ApprovalTokenProtection;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetNotificationProcessor;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.BudgetNotificationRepository;
import br.com.fiap.numberone.billing.budget.infrastructure.notifications.SmtpBudgetEmailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "billing.notifications.enabled", havingValue = "true")
@EnableScheduling
public class BudgetNotificationConfig {
    @Bean
    SmtpBudgetEmailSender smtpBudgetEmailSender(JavaMailSender sender, BudgetNotificationProperties properties) {
        if (sender instanceof JavaMailSenderImpl mail && (mail.getUsername() == null || mail.getUsername().isBlank()
                || mail.getPassword() == null || mail.getPassword().isBlank())) {
            throw new IllegalArgumentException("As credenciais SMTP são obrigatórias quando as notificações estão habilitadas");
        }
        return new SmtpBudgetEmailSender(sender, properties.from());
    }

    @Bean
    BudgetNotificationProcessor budgetNotificationProcessor(BudgetNotificationRepository repository,
            ApprovalTokenProtection protection, SmtpBudgetEmailSender sender, BudgetNotificationProperties properties) {
        repository.validateConfiguration();
        return new BudgetNotificationProcessor(repository, protection, sender, properties);
    }
}
