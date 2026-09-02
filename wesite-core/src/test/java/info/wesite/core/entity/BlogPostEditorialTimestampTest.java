package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Date;

import org.junit.jupiter.api.Test;

class BlogPostEditorialTimestampTest {

    @Test
    void storesEditorialTimestampIndependentlyFromGenericUpdateTime() {
        Date editorialTime = new Date(1_800_000_000_000L);
        Date genericUpdateTime = new Date(1_700_000_000_000L);
        BlogPost post = new BlogPost();

        post.setContentUpdatedAt(editorialTime);
        post.setUpdateTime(genericUpdateTime);

        assertSame(editorialTime, post.getContentUpdatedAt());
        assertSame(genericUpdateTime, post.getUpdateTime());
    }
}
