package info.wesite.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import info.wesite.core.entity.BlogPost;

public interface BlogPostMapper extends BaseMapper<BlogPost> {

    @Select("SELECT * FROM WEB_BLOG_POST WHERE ID = #{id} AND DELETED = 0 FOR UPDATE")
    BlogPost selectByIdForUpdate(@Param("id") String id);
}
