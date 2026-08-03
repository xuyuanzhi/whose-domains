package info.wesite.web.auth;

public record EmailLoginRequestResult(boolean success, String message) {

    public static EmailLoginRequestResult success(String message) {
        return new EmailLoginRequestResult(true, message);
    }

    public static EmailLoginRequestResult failure(String message) {
        return new EmailLoginRequestResult(false, message);
    }
}
