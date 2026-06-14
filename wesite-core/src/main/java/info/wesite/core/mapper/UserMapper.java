package info.wesite.core.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {

}
