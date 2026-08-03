package info.wesite.web.auth.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class GoogleIdentityParserTest {

    private GoogleIdentityParser parser;
    private OidcUser user;

    @BeforeEach
    void setUp() {
        parser = new GoogleIdentityParser();
        user = mock(OidcUser.class);
    }

    @Test
    void classifiesWorkspaceOnlyWhenHostedDomainMatchesEmail() {
        when(user.getSubject()).thenReturn("sub-1");
        when(user.getEmail()).thenReturn(" Person@Example.com ");
        when(user.getEmailVerified()).thenReturn(true);
        when(user.getClaimAsString("hd")).thenReturn("example.com");
        when(user.getFullName()).thenReturn("Person Name");

        GoogleIdentity identity = parser.parse(user);

        assertEquals("person@example.com", identity.email());
        assertTrue(identity.googleManagedEmail());
    }

    @Test
    void classifiesGmailAsGoogleManaged() {
        when(user.getSubject()).thenReturn("sub-2");
        when(user.getEmail()).thenReturn(" Person@Gmail.com ");
        when(user.getEmailVerified()).thenReturn(true);
        when(user.getFullName()).thenReturn("Person Name");

        GoogleIdentity identity = parser.parse(user);

        assertEquals("person@gmail.com", identity.email());
        assertTrue(identity.googleManagedEmail());
    }

    @Test
    void doesNotClassifyMismatchedHostedDomainAsGoogleManaged() {
        when(user.getSubject()).thenReturn("sub-3");
        when(user.getEmail()).thenReturn("person@example.com");
        when(user.getEmailVerified()).thenReturn(true);
        when(user.getClaimAsString("hd")).thenReturn("other.example");
        when(user.getFullName()).thenReturn("Person Name");

        GoogleIdentity identity = parser.parse(user);

        assertFalse(identity.googleManagedEmail());
    }

    @Test
    void rejectsMissingSubject() {
        when(user.getEmail()).thenReturn("person@example.com");
        when(user.getEmailVerified()).thenReturn(true);

        GoogleLoginException exception = assertThrows(GoogleLoginException.class, () -> parser.parse(user));

        assertEquals(GoogleLoginException.Code.INVALID_IDENTITY, exception.code());
    }

    @Test
    void rejectsMissingEmail() {
        when(user.getSubject()).thenReturn("sub-4");
        when(user.getEmailVerified()).thenReturn(true);

        GoogleLoginException exception = assertThrows(GoogleLoginException.class, () -> parser.parse(user));

        assertEquals(GoogleLoginException.Code.INVALID_IDENTITY, exception.code());
    }

    @Test
    void rejectsUnverifiedEmail() {
        when(user.getSubject()).thenReturn("sub-5");
        when(user.getEmail()).thenReturn("person@example.com");
        when(user.getEmailVerified()).thenReturn(false);

        GoogleLoginException exception = assertThrows(GoogleLoginException.class, () -> parser.parse(user));

        assertEquals(GoogleLoginException.Code.UNVERIFIED_EMAIL, exception.code());
    }

    @Test
    void rejectsMissingEmailVerifiedClaim() {
        when(user.getSubject()).thenReturn("sub-6");
        when(user.getEmail()).thenReturn("person@example.com");
        when(user.getEmailVerified()).thenReturn(null);

        GoogleLoginException exception = assertThrows(GoogleLoginException.class, () -> parser.parse(user));

        assertEquals(GoogleLoginException.Code.UNVERIFIED_EMAIL, exception.code());
    }
}
