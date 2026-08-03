package info.wesite.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM SYS_USER WHERE ID = #{id} AND DELETED = 0 FOR UPDATE")
    User selectByIdForUpdate(@Param("id") String id);

    @Select("SELECT * FROM SYS_USER WHERE GOOGLE_SUB = #{subject} AND DELETED = 0 FOR UPDATE")
    User selectByGoogleSubForUpdate(@Param("subject") String subject);

    @Select("SELECT * FROM SYS_USER WHERE EMAIL = #{email} AND DELETED = 0 FOR UPDATE")
    User selectByEmailForUpdate(@Param("email") String email);
}
