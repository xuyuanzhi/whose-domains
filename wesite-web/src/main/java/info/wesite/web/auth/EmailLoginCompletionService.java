package info.wesite.web.auth;

import java.util.Date;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.entity.BaseEntity;
import info.wesite.core.entity.User;
import info.wesite.core.mapper.UserMapper;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.web.auth.google.GoogleLoginService;
import info.wesite.web.auth.google.GoogleLoginException;
import info.wesite.web.auth.google.PendingGoogleBinding;

@Service
public class EmailLoginCompletionService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final GoogleLoginService googleLoginService;

    public EmailLoginCompletionService(UserService userService, UserMapper userMapper,
            GoogleLoginService googleLoginService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.googleLoginService = googleLoginService;
    }

    @Transactional
    public User complete(String email, PendingGoogleBinding pending) {
        User user = userService.getOne(new QueryWrapper<User>().eq("EMAIL", email));
        if (user == null) {
            user = create(email);
        }
        if (!Integer.valueOf(BaseEntity.STATUS_ACTIVE).equals(user.getStatus())) {
            throw new GoogleLoginException(GoogleLoginException.Code.INACTIVE_USER);
        }
        return pending == null ? user : googleLoginService.completeConfirmedBinding(user, pending);
    }

    private User create(String email) {
        User user = new User();
        user.setId(RandomUtils.generateId());
        user.setEmail(email);
        user.setName(email.split("@", 2)[0]);
        user.setSecureKey(RandomUtils.generateId());
        user.setCreateBy(email);
        user.setCreateTime(new Date());
        user.setStatus(User.STATUS_ACTIVE);
        user.setUserType(User.TYPE_PERSON);
        try {
            if (!userService.save(user)) {
                throw new IllegalStateException("Email user could not be created");
            }
            return user;
        } catch (DuplicateKeyException duplicate) {
            User concurrentUser = userMapper.selectByEmailForUpdate(email);
            if (concurrentUser != null) {
                return concurrentUser;
            }
            throw duplicate;
        }
    }
}
