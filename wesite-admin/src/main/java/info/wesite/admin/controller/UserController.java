package info.wesite.admin.controller;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.view.AdminUserView;
import info.wesite.admin.view.SearchParam;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/user")
public class UserController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_LIMIT = 20;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/list")
    public ResponseJson<AdminUserView> list(@RequestBody SearchParam param) {
        int pageNumber = param.getPage() == null ? DEFAULT_PAGE : param.getPage();
        int pageSize = param.getLimit() == null ? DEFAULT_LIMIT : param.getLimit();
        String keyword = StringUtils.trimToNull(param.getKeyword());

        QueryWrapper<User> query = new QueryWrapper<User>()
            .eq("USER_TYPE", User.TYPE_PERSON);
        if (keyword != null) {
            query.and(nested -> nested.like("NAME", keyword)
                .or()
                .like("PHONE_NO", keyword));
        }

        Page<User> page = userService.page(Page.of(pageNumber, pageSize), query);
        List<AdminUserView> users = page.getRecords().stream()
            .map(AdminUserView::from)
            .toList();
        return ResponseJson.success(users, page.getTotal());
    }

    @PostMapping("/detail")
    public ResponseJson<AdminUserView> detail(@RequestBody User param) {
        String id = param == null ? null : StringUtils.trimToNull(param.getId());
        if (id == null) {
            return ResponseJson.failure("用户ID不能为空");
        }

        User user = userService.getById(id);
        if (!isPerson(user)) {
            return ResponseJson.failure("用户不存在");
        }

        return ResponseJson.success(AdminUserView.from(user));
    }

    @PostMapping("/save")
    public ResponseJson<AdminUserView> save(@RequestBody User param) {
        String name = param == null ? null : StringUtils.trimToNull(param.getName());
        String phoneNo = param == null ? null : StringUtils.trimToNull(param.getPhoneNo());
        Integer status = param == null ? null : param.getStatus();
        if (name == null) {
            return ResponseJson.failure("用户名称不能为空");
        }
        if (phoneNo == null) {
            return ResponseJson.failure("用户手机不能为空");
        }
        if (!isEditableStatus(status)) {
            return ResponseJson.failure("用户状态只能是启用或禁用");
        }

        String id = StringUtils.trimToNull(param.getId());
        User user;
        Date now = new Date();
        if (id == null) {
            user = new User();
            user.setId(RandomUtils.generateId());
            user.setUserType(User.TYPE_PERSON);
            user.setCreateBy(currentActorId());
            user.setCreateTime(now);
        } else {
            user = userService.getById(id);
            if (!isPerson(user)) {
                return ResponseJson.failure("用户不存在");
            }
            user.setUpdateBy(currentActorId());
            user.setUpdateTime(now);
        }

        QueryWrapper<User> duplicatePhone = new QueryWrapper<User>()
            .eq("PHONE_NO", phoneNo);
        if (id != null) {
            duplicatePhone.ne("ID", id);
        }
        if (userService.count(duplicatePhone) > 0) {
            return ResponseJson.failure("手机号已存在");
        }

        user.setName(name);
        user.setPhoneNo(phoneNo);
        user.setStatus(status);
        try {
            if (userService.saveOrUpdate(user)) {
                return ResponseJson.success();
            }
        } catch (DuplicateKeyException exception) {
            return ResponseJson.failure("手机号已存在");
        }
        return ResponseJson.failure("保存失败");
    }

    @PostMapping("/delete")
    public ResponseJson<AdminUserView> delete(@RequestBody User param) {
        String id = param == null ? null : StringUtils.trimToNull(param.getId());
        if (id == null) {
            return ResponseJson.failure("用户ID不能为空");
        }

        User user = userService.getById(id);
        if (!isPerson(user)) {
            return ResponseJson.failure("用户不存在");
        }

        if (userService.removeById(id)) {
            return ResponseJson.success();
        }
        return ResponseJson.failure("删除失败");
    }

    private static boolean isPerson(User user) {
        return user != null && User.TYPE_PERSON.equals(user.getUserType());
    }

    private static boolean isEditableStatus(Integer status) {
        return status != null
            && (status == User.STATUS_ACTIVE || status == User.STATUS_INACTIVE);
    }

    private static String currentActorId() {
        User actor = UserHolder.get();
        return actor == null ? null : actor.getId();
    }
}
