package com.petmarketplace;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.application.admin.dto.ListingModerateRequest;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.ProfileRepository;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.infrastructure.security.JwtTokenProvider;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestExecutionListeners.MergeMode;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two run modes, selected by the {@code tests.mode} system property:
 * <ul>
 *   <li><b>embedded</b> (default) — the original behaviour: Postgres + Redis are started in
 *       Testcontainers, the full app boots in-JVM on a random port, and the RestClient targets
 *       that port. Requires Docker.</li>
 *   <li><b>stand</b> ({@code -Dtests.mode=stand}) — no Testcontainers. The app still boots in-JVM,
 *       but its DataSource/Redis/JWT secret point at an already-running external stand (local
 *       {@code docker-compose up -d && gradle bootRun} or remote, configured by the {@code stand}
 *       profile / {@code STAND_*} env vars). The RestClient targets the stand's HTTP base URL
 *       ({@code tests.base-url}) instead of the in-JVM port. Test data is seeded before each
 *       class and cleaned up after by {@link StandDataTestExecutionListener}.</li>
 * </ul>
 * Stand mode is needed because public registration only creates BUYER accounts, so the tests
 * still create SELLER/ADMIN/MODERATOR users by writing straight to the shared DB — which only
 * works when the test JVM shares the stand's DB and JWT secret.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(resolver = IntegrationTestBase.StandAwareProfileResolver.class)
// Replaces @Testcontainers(disabledWithoutDocker = true): stand mode must NOT be skipped for lack
// of Docker, while embedded mode still must be. See TestModeCondition.
@org.junit.jupiter.api.extension.ExtendWith(TestModeCondition.class)
@TestExecutionListeners(
        listeners = StandDataTestExecutionListener.class,
        mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
public abstract class IntegrationTestBase {

    /** Set by the gradle {@code testOnStand} task via {@code -Dtests.mode=stand}. */
    static final boolean STAND_MODE = "stand".equalsIgnoreCase(System.getProperty("tests.mode"));

    /**
     * Outcome of the embedded-mode container start, read by {@link TestModeCondition}. Stays
     * {@code false} in stand mode (containers are never started there, and the condition enables
     * stand mode unconditionally).
     */
    static boolean dockerAvailable = false;

    protected static final UUID DOGS_CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final UUID LABRADOR_BREED_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.1";

    // Shared containers: started ONCE per JVM and kept alive for the whole test run. Using
    // @Container instead would stop each container after its test class completes, but the
    // Spring ApplicationContext (and its DataSource) is cached and reused across test classes —
    // and a restarted container gets a new random host port, so the cached DataSource would point
    // at a dead port and every DB-touching test would fail with a ConnectException. Starting them
    // manually (no @Container) keeps the port stable for the JVM lifetime.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("petmarketplace")
            .withUsername("petmarketplace")
            .withPassword("petmarketplace");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    // Shared Kafka broker for the animal-info request/reply integration tests. Same shared-container
    // rationale as POSTGRES/REDIS: started once per JVM in the static block (NOT @Container), so the
    // cached Spring context's @KafkaListener container keeps a stable bootstrap address across test
    // classes. In stand mode this is never started (the stand profile supplies kafka.bootstrap-servers).
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

    static {
        if (!STAND_MODE) {
            try {
                POSTGRES.start();
                REDIS.start();
                KAFKA.start();
                dockerAvailable = true;
            } catch (Exception e) {
                // Docker unavailable: leave containers unstarted. TestModeCondition then disables
                // every test before the context is created, so the suppliers below are never read.
                dockerAvailable = false;
            }
        }
    }

    /**
     * Activates {@code test} in embedded mode, and {@code test + stand} in stand mode (so
     * {@code application-stand.yml} overlays the stand's DB/Redis/JWT/base-url on top of
     * {@code application-test.yml}).
     */
    static class StandAwareProfileResolver implements ActiveProfilesResolver {
        @Override
        public String[] resolve(Class<?> testClass) {
            return STAND_MODE ? new String[] {"test", "stand"} : new String[] {"test"};
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // In stand mode the stand profile supplies the datasource/redis (and we must NOT override
        // them with the unstarted Testcontainers' suppliers, which would NPE / point nowhere).
        if (STAND_MODE) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    private int port;

    /**
     * Stand's HTTP base URL (from {@code tests.base-url}, set by the {@code stand} profile). Empty
     * in embedded mode, where the RestClient targets the in-JVM server's random port instead.
     */
    @Value("${tests.base-url:}")
    private String standBaseUrl;

    protected RestClient restClient;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ProfileRepository profileRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @org.junit.jupiter.api.BeforeEach
    void setUpRestClient() {
        // Spring Boot 4 no longer auto-configures a RestClient.Builder bean, so build the client
        // directly. The default converters handle records and Java time; tests that need to inspect
        // raw JSON read the body as a String and parse it (see #parse) instead of deserializing
        // straight into JsonNode, which the default converter mishandles. @LocalServerPort is
        // resolved during context initialization, so it is set by the time @BeforeEach runs.
        //
        // In stand mode the RestClient targets the external stand (tests.base-url from the stand
        // profile) rather than the in-JVM server, which runs unused on its random port.
        String baseUrl = STAND_MODE && standBaseUrl != null && !standBaseUrl.isBlank()
                ? standBaseUrl
                : "http://localhost:" + port + "/api/v1";
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    protected TestUser createUser(Role role, String suffix) {
        String baseEmail = role.name().toLowerCase() + "_" + suffix;
        String email = baseEmail + "@example.com";
        String password = "Password1!";

        userRepository.findByEmail(email).ifPresent(userRepository::delete);

        User user = User.builder()
                .email(email)
                .firstName(role.name())
                .lastName("Test")
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .verified(true)
                .active(true)
                .build();
        User saved = userRepository.save(user);

        Profile profile = Profile.builder()
                .user(saved)
                .city("Moscow")
                .country("Russia")
                .rating(BigDecimal.ZERO)
                .totalReviews(0)
                .build();
        profileRepository.save(profile);

        return new TestUser(saved.getId(), email, password, role);
    }

    protected TestUser createUniqueUser(Role role) {
        return createUser(role, UUID.randomUUID().toString().substring(0, 8));
    }

    protected HttpHeaders authHeader(TestUser user) {
        String token = jwtTokenProvider.generateAccessToken(user.email(), user.role());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected Consumer<HttpHeaders> authHeaders(TestUser user) {
        return user == null
                ? headers -> { }
                : headers -> headers.setBearerAuth(jwtTokenProvider.generateAccessToken(user.email(), user.role()));
    }

    protected HttpHeaders authHeader(String email, Role role) {
        String token = jwtTokenProvider.generateAccessToken(email, role);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * Creates a listing as {@code seller} (status PENDING_MODERATION) and approves it via
     * {@code admin}, returning the resulting ACTIVE listing. Reused across many test modules.
     */
    protected ListingResponse createActiveListing(TestUser seller, TestUser admin) {
        return createActiveListing(seller, admin, "Test puppy", BigDecimal.valueOf(30000));
    }

    protected ListingResponse createActiveListing(TestUser seller, TestUser admin, String title, BigDecimal price) {
        ListingCreateRequest createRequest = new ListingCreateRequest(
                DOGS_CATEGORY_ID,
                LABRADOR_BREED_ID,
                title,
                "Test description",
                price,
                "RUB",
                ListingGender.MALE,
                3,
                "Black",
                BigDecimal.valueOf(10.0),
                "Healthy",
                true,
                true,
                "Russia",
                "Moscow"
        );

        ResponseEntity<ListingResponse> createResponse = restClient.post()
                .uri("/listings")
                .body(createRequest)
                .headers(authHeaders(seller))
                .retrieve()
                .toEntity(ListingResponse.class);
        assertThat(createResponse.getBody()).isNotNull();
        UUID listingId = createResponse.getBody().id();

        ListingModerateRequest moderateRequest = new ListingModerateRequest(ListingStatus.ACTIVE, "Approved");
        ResponseEntity<ListingResponse> moderateResponse = restClient.put()
                .uri("/admin/listings/" + listingId + "/moderate")
                .body(moderateRequest)
                .headers(authHeaders(admin))
                .retrieve()
                .toEntity(ListingResponse.class);
        assertThat(moderateResponse.getBody()).isNotNull();
        return moderateResponse.getBody();
    }

    // ---------------------------------------------------------------------
    // Status-capturing request helpers.
    //
    // The default RestClient .retrieve() throws on any 4xx/5xx. For tests that
    // assert non-2xx responses (401/403/404/409/400) we install a no-op error
    // handler matching every status, so the ResponseEntity carries the real status
    // code and the raw (ApiError JSON) body as a String instead of throwing.
    // ---------------------------------------------------------------------

    private static final java.util.function.Predicate<org.springframework.http.HttpStatusCode> ALWAYS =
            status -> true;
    private static final RestClient.ResponseSpec.ErrorHandler NOOP = (req, res) -> { };

    protected ResponseEntity<String> getStatus(String uri, TestUser asUser) {
        return restClient.get().uri(uri)
                .headers(authHeaders(asUser))
                .retrieve()
                .onStatus(ALWAYS, NOOP)
                .toEntity(String.class);
    }

    protected ResponseEntity<String> postStatus(String uri, Object body, TestUser asUser) {
        return restClient.post().uri(uri)
                .headers(authHeaders(asUser))
                .body(body == null ? "" : body)
                .retrieve()
                .onStatus(ALWAYS, NOOP)
                .toEntity(String.class);
    }

    protected ResponseEntity<String> putStatus(String uri, Object body, TestUser asUser) {
        return restClient.put().uri(uri)
                .headers(authHeaders(asUser))
                .body(body == null ? "" : body)
                .retrieve()
                .onStatus(ALWAYS, NOOP)
                .toEntity(String.class);
    }

    protected ResponseEntity<String> deleteStatus(String uri, TestUser asUser) {
        return restClient.delete().uri(uri)
                .headers(authHeaders(asUser))
                .retrieve()
                .onStatus(ALWAYS, NOOP)
                .toEntity(String.class);
    }

    public record TestUser(UUID id, String email, String password, Role role) {
    }
}
