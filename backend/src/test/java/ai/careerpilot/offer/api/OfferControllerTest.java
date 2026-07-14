package ai.careerpilot.offer.api;

import ai.careerpilot.offer.Offer;
import ai.careerpilot.offer.OfferComparisonService;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controller tests — plain Mockito mocks, no MockMvc/SpringBootTest, per this repo's test convention. */
class OfferControllerTest {

    private OfferRepository repo;
    private OfferComparisonService comparison;
    private AuthenticatedUser user;

    private OfferController controllerEnabled() {
        return new OfferController(repo, comparison, true);
    }

    private OfferController controllerDisabled() {
        return new OfferController(repo, comparison, false);
    }

    private void setUp() {
        repo = mock(OfferRepository.class);
        comparison = mock(OfferComparisonService.class);
        user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "USER");
    }

    @Test
    void listReturnsEmptyWhenDisabled() {
        setUp();
        OfferController controller = controllerDisabled();
        List<Offer> result = controller.list(user);
        assertTrue(result.isEmpty());
        verifyNoInteractions(repo);
    }

    @Test
    void listDelegatesToRepositoryWhenEnabled() {
        setUp();
        when(repo.findByUserIdOrderByCreatedAtDesc(user.userId()))
                .thenReturn(List.of(Offer.builder().userId(user.userId()).companyName("Acme").build()));
        OfferController controller = controllerEnabled();

        List<Offer> result = controller.list(user);

        assertEquals(1, result.size());
        assertEquals("Acme", result.get(0).getCompanyName());
    }

    @Test
    void getThrowsWhenDisabled() {
        setUp();
        OfferController controller = controllerDisabled();
        assertThrows(java.util.NoSuchElementException.class, () -> controller.get(user, UUID.randomUUID()));
    }

    @Test
    void getThrowsForbiddenWhenOwnedByDifferentUser() {
        setUp();
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(java.util.Optional.of(
                Offer.builder().id(id).userId(UUID.randomUUID()).build()));
        OfferController controller = controllerEnabled();

        assertThrows(SecurityException.class, () -> controller.get(user, id));
    }

    @Test
    void getReturnsOfferWhenOwned() {
        setUp();
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(java.util.Optional.of(
                Offer.builder().id(id).userId(user.userId()).companyName("Acme").build()));
        OfferController controller = controllerEnabled();

        assertEquals("Acme", controller.get(user, id).getCompanyName());
    }

    @Test
    void createReturns409WhenDisabled() {
        setUp();
        OfferController controller = controllerDisabled();
        ResponseEntity<?> resp = controller.create(user, new OfferController.ManualOfferRequest(
                null, "Acme", new BigDecimal("100000"), null, null, null, null, null, "USD"));
        assertEquals(409, resp.getStatusCode().value());
        verifyNoInteractions(repo);
    }

    @Test
    void createSavesOfferWhenEnabled() {
        setUp();
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OfferController controller = controllerEnabled();

        ResponseEntity<?> resp = controller.create(user, new OfferController.ManualOfferRequest(
                null, "Acme", new BigDecimal("100000"), null, null, null, null, null, "USD"));

        assertEquals(200, resp.getStatusCode().value());
        Offer saved = (Offer) resp.getBody();
        assertNotNull(saved);
        assertEquals("MANUAL", saved.getSource());
        assertEquals(user.userId(), saved.getUserId());
    }

    @Test
    void compareReturnsEmptyWhenDisabled() {
        setUp();
        OfferController controller = controllerDisabled();
        var result = controller.compare(user, List.of(UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(result.rows().isEmpty());
        verifyNoInteractions(comparison);
    }

    @Test
    void compareDelegatesToServiceWhenEnabled() {
        setUp();
        var expected = new OfferComparisonService.ComparisonResult(List.of(), null);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(comparison.compare(user.userId(), ids)).thenReturn(expected);
        OfferController controller = controllerEnabled();

        assertSame(expected, controller.compare(user, ids));
        verify(comparison).compare(user.userId(), ids);
    }
}
