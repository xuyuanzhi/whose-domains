package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.User;
import info.wesite.core.mapper.UserMapper;
import info.wesite.core.service.UserService;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {


}
