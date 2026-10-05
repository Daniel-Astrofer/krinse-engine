package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Domain-neutral result of the Lightning outbound jamming-risk probe.
 * @param allowed whether current evidence permits the operation
 * @param hardBlock whether policy must reject regardless of beta-mode enforcement
 * @param reason stable diagnostic reason returned by the probe
 */
public record SettlementJammingCheck(boolean allowed, boolean hardBlock, String reason) {}
