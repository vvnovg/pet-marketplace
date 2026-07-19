package com.petmarketplace;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Replaces {@code @Testcontainers(disabledWithoutDocker = true)} with a mode-aware rule so the
 * suite can run in either of two modes without that annotation forcing a skip in stand mode:
 * <ul>
 *   <li><b>stand</b> ({@code -Dtests.mode=stand}) — always enabled. The tests talk to an external
 *       stand over HTTP/JDBC and do not need Docker at all, so a CI box without Docker must still
 *       run them.</li>
 *   <li><b>embedded</b> (default) — enabled only when Docker is available, so a machine without
 *       Docker skips cleanly instead of failing with connection errors. Docker availability is
 *       determined by the {@link IntegrationTestBase} static block, which attempts to start the
 *       shared Postgres/Redis containers and records the outcome in
 *       {@code IntegrationTestBase.dockerAvailable}. Referencing that field forces the base class
 *       to initialize first, so the flag is set before this condition decides.</li>
 * </ul>
 * Registered on {@link IntegrationTestBase} via {@code @ExtendWith}; subclasses inherit it.
 */
public class TestModeCondition implements ExecutionCondition {

    private static final boolean STAND_MODE = "stand"
            .equalsIgnoreCase(System.getProperty("tests.mode"));

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (STAND_MODE) {
            return ConditionEvaluationResult.enabled(
                    "stand mode — testing an external stand (no Docker required)");
        }
        // Referencing the field forces IntegrationTestBase class init (and its static container
        // start) before we read the outcome.
        if (IntegrationTestBase.dockerAvailable) {
            return ConditionEvaluationResult.enabled("embedded Testcontainers mode");
        }
        return ConditionEvaluationResult.disabled(
                "Docker is unavailable — embedded Testcontainers mode needs it. "
                        + "Run against an external stand with -Dtests.mode=stand.");
    }
}