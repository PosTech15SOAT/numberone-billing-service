package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static br.com.fiap.numberone.billing.payment.support.MercadoPagoWebhookFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MercadoPagoOrderDetailsMapperTest {
    private final MercadoPagoOrderDetailsMapper mapper = new MercadoPagoOrderDetailsMapper();

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"status_detail\":null"})
    void shouldAcceptCreatedOrderWithoutStatusDetailAsObservedFromProvider(String detail) {
        var result = mapper.toSnapshot("ORD01ABC", ("""
                {"id":"ORD01ABC","type":"online","processing_mode":"manual",
                 "status":"created","total_amount":"10.00","currency":"BRL",
                 "external_reference":"873157ce-ac37-44e5-b74c-b7a26b055205"
                """ + detail + "}").getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo("created");
        assertThat(result.statusDetail()).isNull();
        assertThat(result.payments()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"processed", "refunded", "processing", "failed"})
    void shouldStillRejectMissingStatusDetailForOtherStates(String status) {
        assertThatThrownBy(() -> mapper.toSnapshot("ORD01ABC", ("""
                {"id":"ORD01ABC","type":"online","processing_mode":"manual",
                 "status":"%s","total_amount":"10.00","currency":"BRL",
                 "external_reference":"873157ce-ac37-44e5-b74c-b7a26b055205"}
                """.formatted(status)).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"transactions\":null", ",\"transactions\":{}",
            ",\"transactions\":{\"payments\":null}", ",\"transactions\":{\"payments\":[]}"})
    void shouldKeepMissingPaymentEvidenceEmpty(String transactions) {
        ProviderOrderSnapshot result = snapshot(transactions);

        assertThat(result.payments()).isEmpty();
        assertThat(result.hasRefundsOrChargebacks()).isFalse();
        assertThat(result.totalPaidAmount()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"refunds\":null,\"chargebacks\":null}",
            "{\"refunds\":[],\"chargebacks\":[]}"})
    void shouldNotReportMissingOrEmptyAdjustments(String transactions) {
        assertThat(snapshot(",\"transactions\":" + transactions).hasRefundsOrChargebacks()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"refunds", "chargebacks"})
    void shouldReportAdjustmentsWhilePreservingPaymentEvidence(String adjustment) {
        String transactions = ",\"transactions\":{\"payments\":[" + payment() + "],\"" + adjustment + "\":[{}]}";

        ProviderOrderSnapshot result = snapshot(transactions);

        assertThat(result.hasRefundsOrChargebacks()).isTrue();
        assertThat(result.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.providerPaymentId()).isEqualTo("PAY01ABC");
            assertThat(payment.amount()).isEqualByComparingTo("10.00");
            assertThat(payment.paidAmount()).isEqualByComparingTo("10.00");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{\"payments\":{}}", "{\"refunds\":{}}", "{\"chargebacks\":{}}",
            "{\"payments\":[null]}", "{\"payments\":[42]}", "{\"payments\":[{}]}"})
    void shouldRejectMalformedTransactionCollections(String transactions) {
        assertThatThrownBy(() -> snapshot(",\"transactions\":" + transactions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProviderOrderSnapshot snapshot(String transactions) {
        String body = """
                {"id":"ORD01ABC","type":"online","processing_mode":"manual",
                 "status":"processed","status_detail":"accredited","total_amount":"10.00",
                 "currency":"BRL","external_reference":"873157ce-ac37-44e5-b74c-b7a26b055205"
                """ + transactions + "}";
        return mapper.toSnapshot("ORD01ABC", body.getBytes(StandardCharsets.UTF_8));
    }
}
