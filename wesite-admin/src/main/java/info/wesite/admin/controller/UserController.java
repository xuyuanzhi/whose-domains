package info.wesite.admin.controller;

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.admin.view.SearchParam;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/user")
public class UserController {

	@Autowired
	private UserService userService;

	@PostMapping("/list")
	public ResponseJson<User> list(@RequestBody SearchParam param) {
		if (param.getPage() == null) {
			param.setPage(1);
		}

		if (param.getLimit() == null) {
			param.setLimit(20);
		}

		QueryWrapper<User> query = new QueryWrapper<User>().eq("USER_TYPE", User.TYPE_PERSON);
		if (StringUtils.isNotBlank(param.getKeyword())) {
			query = query.like("NAME", param.getKeyword());
		}

		Page<User> page = userService.page(Page.of(param.getPage(), param.getLimit()), query);

		return ResponseJson.success(page.getRecords(), page.getTotal());
	}

	@PostMapping("/detail")
	public ResponseJson<User> detail(@RequestBody User param) {
		if (StringUtils.isBlank(param.getId())) {
			return ResponseJson.failure("用户ID不能为空");
		}

		User user = userService.getById(param.getId());
		if (user == null || !User.TYPE_PERSON.equals(user.getUserType())) {
			return ResponseJson.failure("用户不存在");
		}

		return ResponseJson.success(user);
	}

	@PostMapping("/save")
	public ResponseJson<User> save(@RequestBody User param) {
		if (StringUtils.isBlank(param.getName())) {
			return ResponseJson.failure("用户名称不能为空");
		}

		if (StringUtils.isBlank(param.getPhoneNo())) {
			return ResponseJson.failure("用户手机不能为空");
		}

		User user = null;
		if (StringUtils.isBlank(param.getId())) {
			user = new User();
			user.setId(RandomUtils.generateId());
			user.setName(null);
			user.setStatus(null);
			user.setCreateBy(null);
			user.setCreateTime(new Date());
		} else {
			user = userService.getById(param.getId());
			if (user == null) {
				return ResponseJson.failure("用户不存在");
			}

			user.setName(null);
			user.setStatus(null);
			user.setUpdateBy(null);
			user.setUpdateTime(new Date());
		}

		if (userService.saveOrUpdate(user)) {
			return ResponseJson.success();
		} else {
			return ResponseJson.failure("保存失败");
		}
	}

	@PostMapping("/delete")
	public ResponseJson<User> delete(@RequestBody User param) {
		if (StringUtils.isBlank(param.getId())) {
			return ResponseJson.failure("用户ID不能为空");
		}

		User user = userService.getById(param.getId());
		if (user == null) {
			return ResponseJson.failure("用户不存在");
		}

		if (userService.removeById(param.getId())) {
			return ResponseJson.success();
		} else {
			return ResponseJson.failure("删除失败");
		}
	}
}
