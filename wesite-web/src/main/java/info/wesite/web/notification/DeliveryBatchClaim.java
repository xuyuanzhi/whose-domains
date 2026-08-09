package info.wesite.web.notification;

public record DeliveryBatchClaim(
    String batchId,
    String userId,
    String emailMode,
    String windowKey,
    int attempt,
    String claimToken) {
}
