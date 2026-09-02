package info.wesite.core.blog;

import java.util.Date;

import org.springframework.stereotype.Component;

@Component
public class BlogTimeProvider {

    public Date now() {
        return new Date();
    }
}
