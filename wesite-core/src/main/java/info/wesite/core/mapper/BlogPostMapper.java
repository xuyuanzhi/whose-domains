package info.wesite.core.mapper;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import info.wesite.core.entity.BlogPost;

public interface BlogPostMapper extends BaseMapper<BlogPost> {

    @Select("SELECT ID FROM WEB_BLOG_EDITORIAL_LOCK WHERE ID = 1 FOR UPDATE")
    Integer lockEditorialWrites();

    @Select("SELECT ID, SLUG, TITLE, SUMMARY, CONTENT, META_DESCRIPTION, STATUS FROM WEB_BLOG_POST "
        + "WHERE DELETED = 0 AND (#{afterId} IS NULL OR ID > #{afterId}) ORDER BY ID LIMIT #{limit}")
    List<BlogPost> selectReviewBatch(@Param("afterId") String afterId, @Param("limit") int limit);

    @Select("SELECT * FROM WEB_BLOG_POST WHERE ID = #{id} AND DELETED = 0 FOR UPDATE")
    BlogPost selectByIdForUpdate(@Param("id") String id);

    @Select("SELECT ID, SLUG, CONTENT, CONTENT_UPDATED_AT FROM WEB_BLOG_POST "
        + "WHERE DELETED = 0 AND (#{afterId} IS NULL OR ID > #{afterId}) "
        + "ORDER BY ID LIMIT #{limit}")
    List<BlogPost> selectSanitizationBatch(
        @Param("afterId") String afterId,
        @Param("limit") int limit);

    @Update("UPDATE WEB_BLOG_POST SET CONTENT = #{content}, "
        + "CONTENT_UPDATED_AT = #{contentUpdatedAt}, UPDATE_BY = #{updateBy} "
        + "WHERE ID = #{id} AND DELETED = 0")
    int updateSanitizedContent(
        @Param("id") String id,
        @Param("content") String content,
        @Param("contentUpdatedAt") Date contentUpdatedAt,
        @Param("updateBy") String updateBy);
}
