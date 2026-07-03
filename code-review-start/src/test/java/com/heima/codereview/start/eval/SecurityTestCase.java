package com.heima.codereview.start.eval;

public record SecurityTestCase(
    String id,
    String cweId,
    String fileName,
    String code,
    boolean hasVulnerability,
    String vulnerabilityDescription
) {}
