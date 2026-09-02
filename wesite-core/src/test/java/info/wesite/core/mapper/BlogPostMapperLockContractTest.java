package info.wesite.core.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Locale;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class BlogPostMapperLockContractTest {

    @Test
    void lockedLookupExcludesDeletedRowsAndUsesForUpdate() throws Exception {
        Method lookup = BlogPostMapper.class.getMethod("selectByIdForUpdate", String.class);
        Select select = lookup.getAnnotation(Select.class);

        assertNotNull(select);
        String sql = String.join(" ", select.value()).toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("DELETED = 0"));
        assertTrue(sql.contains("FOR UPDATE"));
    }
}
