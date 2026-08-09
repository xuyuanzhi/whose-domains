package info.wesite.web.notification;

public record DeliveryBatchClaim(
    String batchId,
    String userId,
    String emailMode,
    String windowKey,
    int attempt,
    String claimToken,
    String recipientEmail) {

    public DeliveryBatchClaim(
        String batchId,
        String userId,
        String emailMode,
        String windowKey,
        int attempt,
        String claimToken) {
        this(batchId, userId, emailMode, windowKey, attempt, claimToken, null);
    }
}
