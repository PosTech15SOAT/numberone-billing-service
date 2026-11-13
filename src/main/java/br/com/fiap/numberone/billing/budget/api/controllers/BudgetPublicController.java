package br.com.fiap.numberone.billing.budget.api.controllers;

import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import br.com.fiap.numberone.billing.budget.api.publicview.PublicBudgetRequest;
import br.com.fiap.numberone.billing.budget.api.publicview.PublicBudgetRequestFilter;
import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetAuthorizationException;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetApprovalPageUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetRemotelyUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetRemotelyUseCase;
import br.com.fiap.numberone.billing.budget.infrastructure.logging.SafeExceptionDetails;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

@Controller
@RequestMapping(PublicBudgetRequestFilter.BASE_PATH)
public class BudgetPublicController {
    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetPublicController.class);
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss").withZone(ZoneOffset.UTC);
    private final GetBudgetApprovalPageUseCase getBudget;
    private final ApproveBudgetRemotelyUseCase approveBudget;
    private final RejectBudgetRemotelyUseCase rejectBudget;

    public BudgetPublicController(GetBudgetApprovalPageUseCase getBudget, ApproveBudgetRemotelyUseCase approveBudget,
                                  RejectBudgetRemotelyUseCase rejectBudget) {
        this.getBudget = getBudget;
        this.approveBudget = approveBudget;
        this.rejectBudget = rejectBudget;
    }

    @GetMapping("/decisao")
    public ModelAndView view(HttpServletRequest request, HttpServletResponse response) {
        return respond(response, () -> {
            var data = data(request);
            var budget = getBudget.execute(data.token());
            // O modelo mantém o token no objeto com toString protegido para os logs do MVC.
            return new ModelAndView("budget/decision")
                    .addObject("budget", budget)
                    .addObject("authorization", data)
                    .addObject("expiresAt", DEADLINE.format(budget.expiresAt()));
        });
    }

    @PostMapping("/aprovar")
    public ModelAndView approve(HttpServletRequest request, HttpServletResponse response) {
        return respond(response, () -> {
            var data = data(request);
            approveBudget.execute(data.token(), data.confirmed());
            return message("Orçamento aprovado", "Sua aprovação foi registrada com sucesso. Obrigado!");
        });
    }

    @PostMapping("/rejeitar")
    public ModelAndView reject(HttpServletRequest request, HttpServletResponse response) {
        return respond(response, () -> {
            var data = data(request);
            rejectBudget.execute(data.token(), data.confirmed());
            return message("Orçamento rejeitado", "Sua rejeição foi registrada com sucesso. Entre em contato com a oficina se precisar de ajuda.");
        });
    }

    private ModelAndView authorizationError(BudgetAuthorizationException exception, HttpServletResponse response) {
        int status = switch (exception.getReason()) {
            case INVALID -> 404;
            case EXPIRED, REVOKED -> 410;
            case CONFIRMATION_REQUIRED -> 400;
            default -> 409;
        };
        String title = switch (exception.getReason()) {
            case INVALID -> "Link inválido";
            case EXPIRED -> "Link expirado";
            case REVOKED -> "Link revogado";
            case CONSUMED -> "Link já utilizado";
            case ALREADY_DECIDED -> "Orçamento já decidido";
            case OBSOLETE -> "Revisão desatualizada";
            case CONFIRMATION_REQUIRED -> "Confirme sua decisão";
        };
        response.setStatus(status);
        return message(title, exception.getMessage());
    }

    private ModelAndView respond(HttpServletResponse response, Supplier<ModelAndView> render) {
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        try {
            return render.get();
        } catch (BudgetAuthorizationException exception) {
            return authorizationError(exception, response);
        } catch (OptimisticLockException | OptimisticLockingFailureException exception) {
            response.setStatus(409);
            return message("O orçamento foi atualizado", "Outra operação alterou este orçamento. Consulte novamente o link antes de decidir.");
        } catch (RuntimeException exception) {
            // Não encaminhar mensagens arbitrárias ao resolver MVC, que pode registrá-las em DEBUG.
            ObservationLog.safely(() -> LOGGER.atError().addKeyValue("event.code", "BUDGET_PUBLIC_REQUEST_FAILED")
                    .addKeyValue("error.type", exception.getClass().getName())
                    .addKeyValue("error.stack_trace", SafeExceptionDetails.stackTrace(exception))
                    .log("Falha ao atender a jornada pública de orçamento"));
            response.setStatus(500);
            return message("Não foi possível concluir", "Ocorreu um erro inesperado. Tente novamente mais tarde ou entre em contato com a oficina.");
        }
    }

    private PublicBudgetRequest data(HttpServletRequest request) {
        Object value = request.getAttribute(PublicBudgetRequest.ATTRIBUTE);
        if (value instanceof PublicBudgetRequest data) return data;
        throw new BudgetAuthorizationException(BudgetAuthorizationException.Reason.INVALID);
    }

    private ModelAndView message(String title, String message) {
        return new ModelAndView("budget/decision-result").addObject("title", title).addObject("message", message);
    }
}
