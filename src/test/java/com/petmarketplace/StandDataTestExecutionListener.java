package com.petmarketplace;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Runs the stand-mode SQL scripts against the stand's shared database.
 * <p>
 * Only active when {@code tests.mode=stand} (the same switch {@link IntegrationTestBase} branches
 * on). In that mode the test JVM shares the stand's database, so seed/cleanup can be executed
 * directly through the context's {@link DataSource} — no external {@code psql} or docker exec
 * needed, and it works equally against a local or remote stand as long as the test JVM can reach
 * the stand's Postgres.
 * <p>
 * {@code beforeTestClass} runs {@code stand/seed.sql} (clears leftovers from a prior run and
 * guarantees the reference category/breed rows exist). {@code afterTestClass} runs
 * {@code stand/cleanup.sql} (removes everything the class just created) and runs even when the
 * class fails, so the stand is always left clean. Both fire once per test class; the Spring
 * ApplicationContext (and its DataSource) is cached and reused across classes.
 * <p>
 * Registered via {@code @TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)} on
 * {@link IntegrationTestBase} so the default listeners (dependency injection, etc.) are preserved.
 */
public class StandDataTestExecutionListener implements TestExecutionListener {

    private static final boolean STAND_MODE = "stand"
            .equalsIgnoreCase(System.getProperty("tests.mode"));

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!STAND_MODE) {
            return;
        }
        execute(testContext, "stand/seed.sql");
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (!STAND_MODE) {
            return;
        }
        execute(testContext, "stand/cleanup.sql");
    }

    private void execute(TestContext testContext, String classpathResource) {
        DataSource dataSource = testContext.getApplicationContext().getBean(DataSource.class);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource(classpathResource));
        populator.setSeparator(";");
        populator.execute(dataSource);
    }
}