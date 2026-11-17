from pathlib import Path
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "validate-deploy-config.sh"


class DeployConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.config = {
            "BUDGET_NOTIFICATION_ENCRYPTION_KEY": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "BUDGET_APPROVAL_BASE_URL": "https://gateway.example.invalid/api/public/orcamentos-ordem-servico/decisao",
            "BUDGET_NOTIFICATIONS_ENABLED": "false",
            "OUTBOX_ENABLED": "false",
            "MERCADO_PAGO_ENABLED": "false",
            "MERCADO_PAGO_WEBHOOK_ENABLED": "false",
        }

    def validate(self):
        return subprocess.run(["bash", str(SCRIPT)], env=self.config, capture_output=True, text=True)

    def enable_smtp(self):
        self.config.update(BUDGET_NOTIFICATIONS_ENABLED="true", SMTP_HOST="smtp.example.invalid",
                           SMTP_PORT="587", SMTP_FROM="sender@example.invalid",
                           SMTP_USERNAME="user", SMTP_PASSWORD="password")

    def test_disabled_worker_needs_no_smtp_configuration(self):
        self.assertEqual(self.validate().returncode, 0)

    def enable_financial(self):
        self.config.update(MERCADO_PAGO_ENABLED="true", MERCADO_PAGO_WEBHOOK_ENABLED="true",
                           MERCADO_PAGO_ENVIRONMENT="TEST", MERCADO_PAGO_WEBHOOK_ENVIRONMENT="TEST",
                           MERCADO_PAGO_EXPECTED_TEST_SELLER_ID="123", MERCADO_PAGO_TEST_ACCESS_TOKEN="APP_USR-fictitious",
                           MERCADO_PAGO_WEBHOOK_SECRET="fictitious-secret")

    def test_eks_accepts_valid_financial_configuration(self):
        self.enable_financial()
        self.config["KUBERNETES_SERVICE_HOST"] = "10.0.0.1"
        for checkout, webhook in (("true", "false"), ("false", "true"), ("true", "true")):
            with self.subTest(checkout=checkout, webhook=webhook):
                self.config.update(MERCADO_PAGO_ENABLED=checkout, MERCADO_PAGO_WEBHOOK_ENABLED=webhook)
                self.assertEqual(self.validate().returncode, 0)

    def test_financial_configuration_requires_every_field(self):
        self.enable_financial()
        for name in ("MERCADO_PAGO_ENVIRONMENT", "MERCADO_PAGO_WEBHOOK_ENVIRONMENT",
                     "MERCADO_PAGO_EXPECTED_TEST_SELLER_ID", "MERCADO_PAGO_TEST_ACCESS_TOKEN", "MERCADO_PAGO_WEBHOOK_SECRET"):
            with self.subTest(name=name):
                value = self.config.pop(name)
                self.assertNotEqual(self.validate().returncode, 0)
                self.config[name] = value

    def test_invalid_financial_configuration_is_rejected(self):
        for name, value in (("MERCADO_PAGO_ENABLED", "yes"), ("MERCADO_PAGO_ENVIRONMENT", "PRODUCTION"),
                            ("MERCADO_PAGO_WEBHOOK_ENVIRONMENT", "PRODUCTION"),
                            ("MERCADO_PAGO_EXPECTED_TEST_SELLER_ID", "0"),
                            ("MERCADO_PAGO_TEST_ACCESS_TOKEN", "invalid"), ("MERCADO_PAGO_WEBHOOK_SECRET", "  ")):
            with self.subTest(name=name):
                self.enable_financial()
                self.config[name] = value
                self.assertNotEqual(self.validate().returncode, 0)

    def test_enabled_worker_requires_each_smtp_setting(self):
        self.enable_smtp()
        self.assertEqual(self.validate().returncode, 0)
        for name in ("SMTP_HOST", "SMTP_PORT", "SMTP_FROM", "SMTP_USERNAME", "SMTP_PASSWORD"):
            with self.subTest(name=name):
                value = self.config.pop(name)
                self.assertNotEqual(self.validate().returncode, 0)
                self.config[name] = value

    def test_partial_credentials_rejected_even_when_disabled(self):
        self.config["SMTP_USERNAME"] = "user"
        self.assertNotEqual(self.validate().returncode, 0)

    def test_invalid_port_and_flag_rejected(self):
        self.enable_smtp()
        for port in ("0", "65536", "invalid"):
            with self.subTest(port=port):
                self.config["SMTP_PORT"] = port
                self.assertNotEqual(self.validate().returncode, 0)
        self.config["BUDGET_NOTIFICATIONS_ENABLED"] = "yes"
        self.assertNotEqual(self.validate().returncode, 0)

    def test_public_url_must_be_https_and_match_route(self):
        for url in ("http://example.com/api/public/orcamentos-ordem-servico/decisao",
                    "https://example.com/wrong", "https://example.com/api/public/orcamentos-ordem-servico/decisao?token=secret",
                    "https://user@example.com/api/public/orcamentos-ordem-servico/decisao",
                    "https://example.com:99999/api/public/orcamentos-ordem-servico/decisao",
                    "https://..../api/public/orcamentos-ordem-servico/decisao"):
            with self.subTest(url=url):
                self.config["BUDGET_APPROVAL_BASE_URL"] = url
                self.assertNotEqual(self.validate().returncode, 0)


if __name__ == "__main__":
    unittest.main()
