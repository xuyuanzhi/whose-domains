package info.wesite.web.auth.google;

public record GoogleIdentity(String subject, String email, String displayName, boolean googleManagedEmail) {
}
