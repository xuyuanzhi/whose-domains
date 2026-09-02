package info.wesite.core.blog;

public class BlogEditorialException extends RuntimeException {

    public BlogEditorialException(String message) {
        super(message);
    }

    public BlogEditorialException(String message, Throwable cause) {
        super(message, cause);
    }
}
