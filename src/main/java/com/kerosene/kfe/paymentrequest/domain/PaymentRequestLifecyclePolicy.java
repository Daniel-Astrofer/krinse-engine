package com.kerosene.kfe.paymentrequest.domain;

import java.time.LocalDateTime;

/** Pure lifecycle rules shared by API, internal-link and observation adapters. */
public final class PaymentRequestLifecyclePolicy {
    private PaymentRequestLifecyclePolicy() {
    }

    public static boolean isExpired(LocalDateTime expiresAt, LocalDateTime now) {
        if (expiresAt == null || now == null) {
            return false;
        }
        return !expiresAt.isAfter(now);
    }

    public static boolean canObserve(String status) {
        return "OPEN".equals(status) || "EXPIRED".equals(status);
    }

    public static boolean canSettle(String status) {
        return "OPEN".equals(status) || "EXPIRED".equals(status);
    }

    public static boolean canExpire(String status) {
        return "OPEN".equals(status);
    }

    public static boolean canCancel(String status) {
        return "OPEN".equals(status) || "EXPIRED".equals(status) || "FAILED".equals(status);
    }

    public static boolean acceptsAmount(Long requestedSats, long observedSats) {
        return observedSats > 0L && (requestedSats == null || observedSats >= requestedSats);
    }
}
