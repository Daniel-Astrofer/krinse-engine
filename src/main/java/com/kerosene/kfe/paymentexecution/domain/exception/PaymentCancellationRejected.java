package com.kerosene.kfe.paymentexecution.domain.exception;

public final class PaymentCancellationRejected extends IllegalStateException {
    public PaymentCancellationRejected() {
        super("Esta transação já foi iniciada ou observada na rede e não pode ser cancelada.");
    }

    private PaymentCancellationRejected(String message) {
        super(message);
    }

    /** Preserves the public response for a negative preliminary eligibility decision. */
    public static PaymentCancellationRejected notEligible() {
        return new PaymentCancellationRejected(
                "Esta transação não pode ser cancelada (já liquidada, em execução na rede, ou sem invoice aberta).");
    }
}
