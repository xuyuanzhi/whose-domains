package info.wesite.admin.controller;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.TokenUtils;
import info.wesite.core.view.LoginParam;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "主类")
@Controller
public class MainController {

    @Autowired
    private UserService userService;
    
    // 版本号，解决缓存问题
 	private static final String version = "9." + String.valueOf(System.currentTimeMillis());

    @Operation(summary = "首页")
    @GetMapping({ "/", "/index", "/index.html" })
    public String index(Model model) {
    	// 版本号，解决缓存问题
		model.addAttribute("version", version);
        return "index";
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    @ResponseBody
    public ResponseJson<JSONObject> login(@RequestBody LoginParam param) {
        if (StringUtils.isBlank(param.getUsername())) {
            return ResponseJson.failure("用户名不能为空");
        }

        if (StringUtils.isBlank(param.getPassword())) {
            return ResponseJson.failure("密码不能为空");
        }

        User user = userService
                .getOne(new QueryWrapper<User>().eq("PHONE_NO", param.getUsername()).eq("USER_TYPE", User.TYPE_ADMIN));
        if (user == null || user.getStatus() != User.STATUS_ACTIVE) {
            return ResponseJson.failure("用户不存在或已禁用");
        }

        if (!user.getPassword().equals(DigestUtils.md5Hex(param.getPassword() + "#" + user.getSecureKey()))) {
            return ResponseJson.failure("用户名或密码错误");
        }

        String token = TokenUtils.createToken(user, 240);
        if (StringUtils.isBlank(token)) {
            return ResponseJson.failure("登录失败");
        }

        JSONObject json = new JSONObject();
        json.put("token", token);
        json.put("name", user.getName());
        return ResponseJson.success(json);
    }
    
    @GetMapping("/userInfo")
    @ResponseBody
    public ResponseJson<User> userInfo() {
		return ResponseJson.success(UserHolder.get());
	}
    
    @GetMapping("/logout")
    @ResponseBody
    public ResponseJson<User> logout() {
		return ResponseJson.success();
	}
}
