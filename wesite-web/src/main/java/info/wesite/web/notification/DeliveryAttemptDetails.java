package info.wesite.web.notification;

public record DeliveryAttemptDetails(
    String notificationId,
    String eventId,
    String watchId,
    String toEmail,
    String domainName,
    Integer daysLeft,
    String subject,
    String errorMessage) {
}
