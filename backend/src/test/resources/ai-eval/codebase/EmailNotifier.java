package fake.notify;

import fake.user.User;

/**
 * Renders and sends transactional emails — welcome, password reset,
 * payment receipt. Templates live in resources/email-templates/ and
 * are rendered with the {@link Mustache} engine at send time.
 */
public class EmailNotifier {

    private final SmtpClient smtp;
    private final Mustache mustache;

    public EmailNotifier(SmtpClient smtp, Mustache mustache) {
        this.smtp = smtp;
        this.mustache = mustache;
    }

    public void sendWelcomeEmail(User user) {
        String body = mustache.render("welcome", java.util.Map.of(
                "name", user.fullName()
        ));
        smtp.send(user.email(), "Welcome to Codeleon", body);
    }

    public void sendPasswordResetEmail(User user, String resetToken) {
        String body = mustache.render("password-reset", java.util.Map.of(
                "name", user.fullName(),
                "link", "https://codeleon.dev/reset?token=" + resetToken
        ));
        smtp.send(user.email(), "Reset your password", body);
    }

    public void sendPaymentReceipt(User user, String amount, String currency) {
        String body = mustache.render("payment-receipt", java.util.Map.of(
                "name", user.fullName(),
                "amount", amount,
                "currency", currency
        ));
        smtp.send(user.email(), "Receipt", body);
    }
}
