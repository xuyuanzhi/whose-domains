package info.wesite.core.model;

/**
 * Outcome of a quota check. {@code reason} is null when allowed, otherwise "daily" or "monthly"
 * so a client can tell which allowance was exhausted.
 */
public final class QuotaDecision {

    private final boolean allowed;
    private final String reason;
    private final int dailyUsed;
    private final int dailyLimit;
    private final int monthlyUsed;
    private final int monthlyLimit;

    private QuotaDecision(boolean allowed, String reason, int dailyUsed, int dailyLimit,
            int monthlyUsed, int monthlyLimit) {
        this.allowed = allowed;
        this.reason = reason;
        this.dailyUsed = dailyUsed;
        this.dailyLimit = dailyLimit;
        this.monthlyUsed = monthlyUsed;
        this.monthlyLimit = monthlyLimit;
    }

    public static QuotaDecision allowed(int dailyUsed, int dailyLimit, int monthlyUsed, int monthlyLimit) {
        return new QuotaDecision(true, null, dailyUsed, dailyLimit, monthlyUsed, monthlyLimit);
    }

    public static QuotaDecision denied(String reason, int dailyUsed, int dailyLimit,
            int monthlyUsed, int monthlyLimit) {
        return new QuotaDecision(false, reason, dailyUsed, dailyLimit, monthlyUsed, monthlyLimit);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public int getDailyUsed() {
        return dailyUsed;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public int getMonthlyUsed() {
        return monthlyUsed;
    }

    public int getMonthlyLimit() {
        return monthlyLimit;
    }
}
