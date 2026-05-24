package fake.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Routes payment intents through Stripe, applying our retry policy on
 * transient failures and surfacing card-decline errors verbatim so the
 * frontend can render the bank's message.
 */
public class PaymentProcessor {

    private static final int MAX_RETRIES = 3;

    private final StripeClient stripe;
    private final PaymentLedger ledger;

    public PaymentProcessor(StripeClient stripe, PaymentLedger ledger) {
        this.stripe = stripe;
        this.ledger = ledger;
    }

    public PaymentResult charge(UUID customerId, BigDecimal amount, String currency) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String stripeId = stripe.createCharge(customerId.toString(), amount, currency);
                ledger.record(customerId, amount, currency, stripeId);
                return PaymentResult.success(stripeId);
            } catch (StripeTransientException ex) {
                if (attempt == MAX_RETRIES) {
                    return PaymentResult.failed("retries exhausted: " + ex.getMessage());
                }
            } catch (StripeCardDeclinedException ex) {
                return PaymentResult.failed(ex.getMessage());
            }
        }
        return PaymentResult.failed("unreachable");
    }

    public PaymentResult refund(String stripeChargeId) {
        try {
            stripe.createRefund(stripeChargeId);
            ledger.markRefunded(stripeChargeId);
            return PaymentResult.success(stripeChargeId);
        } catch (StripeException ex) {
            return PaymentResult.failed(ex.getMessage());
        }
    }
}
