package info.wesite.web.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import info.wesite.core.config.AccessControl;
import info.wesite.core.config.AccessControl.Level;
import info.wesite.core.entity.User;
import info.wesite.core.service.UserService;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.utils.TokenUtils;
import info.wesite.core.view.ResponseJson;

@Controller
@RequestMapping("/user")
public class UserController {
    
    private static final Logger logger = LoggerFactory.getLogger(UserController.class);
    
    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    
    @Autowired
    private UserService userService;
    
    @PostMapping("/login")
    @ResponseBody
    public ResponseJson<Map<String, Object>> login(@RequestBody User param) {
        if (StringUtils.isBlank(param.getPhoneNo())) {
            return ResponseJson.failure("手机号码不能为空");
        }

        if (StringUtils.isBlank(param.getPassword())) {
            return ResponseJson.failure("密码不能为空");
        }

        User user = userService.getOne(new QueryWrapper<User>().eq("PHONE_NO", param.getPhoneNo().trim()));
        if (user == null || user.getStatus() != User.STATUS_ACTIVE) {
            return ResponseJson.failure("手机号码或密码错误");
        }

        // BCrypt 验证密码
        if (!passwordEncoder.matches(param.getPassword(), user.getPassword())) {
            return ResponseJson.failure("手机号码或密码错误");
        }

        Map<String, Object> json = new HashMap<>();
        json.put("token", TokenUtils.createToken(user, 60 * 24 * 30));
        return ResponseJson.success(json);
    }

    @AccessControl(level = Level.SESSION)
    @PostMapping("/logout")
    @ResponseBody
    public ResponseJson<Map<String, Object>> logout() {
        return ResponseJson.success();
    }

    @PostMapping("/sendVcode")
    @ResponseBody
    public ResponseJson<User> sendVcode(@RequestBody User param) {
        if (StringUtils.isBlank(param.getPhoneNo())) {
            return ResponseJson.failure("手机号码不能为空");
        }

        User user = userService.getOne(new QueryWrapper<User>().eq("PHONE_NO", param.getPhoneNo().trim()));
        if (user == null) {
            user = new User();
            user.setId(RandomUtils.generateId());
            user.setName("");
            user.setPhoneNo(param.getPhoneNo());
            user.setCreateBy(param.getPhoneNo());
            user.setCreateTime(new Date());
            user.setStatus(User.STATUS_NEW);
            if (!userService.save(user)) {
                return ResponseJson.failure("系统错误");
            }
        }

        if (user.getVcodeTime() != null && DateUtils.addSeconds(user.getVcodeTime(), 60).after(new Date())) {
            return ResponseJson.failure("操作频繁，请稍后重试");
        }

        // 发送验证码
        String vcode = RandomUtils.generateVcode();
        logger.info("发送短信验证码：{}", vcode);
        // TODO 发送验证码
        boolean send = true;
        if (!send) {
            logger.error("向手机 {} 发送验证码 {} 失败！", param.getPhoneNo(), vcode);
            return ResponseJson.failure("系统错误");
        }

        user.setVcode(vcode);
        user.setVcodeTime(new Date());
        user.setUpdateBy(param.getPhoneNo());
        user.setUpdateTime(new Date());
        if (userService.updateById(user)) {
            return ResponseJson.success("发送成功", null);
        } else {
            return ResponseJson.failure("发送验证码失败");
        }
    }

    @PostMapping("/create")
    @ResponseBody
    public ResponseJson<User> createUser(@RequestBody User param) {
        if (StringUtils.isBlank(param.getName())) {
            return ResponseJson.failure("名称不能为空");
        }

        if (StringUtils.isBlank(param.getPhoneNo())) {
            return ResponseJson.failure("手机号码不能为空");
        }

        if (StringUtils.isBlank(param.getVcode())) {
            return ResponseJson.failure("验证码不能为空");
        }

        if (StringUtils.isBlank(param.getPassword())) {
            return ResponseJson.failure("密码不能为空");
        }

        User user = userService.getOne(new QueryWrapper<User>().eq("PHONE_NO", param.getPhoneNo().trim()));
        if (user == null) {
            return ResponseJson.failure("请先获取验证码");
        }

        if (user.getStatus() != User.STATUS_NEW) {
            return ResponseJson.failure("系统错误");
        }

        if (user.getVcodeTime() == null || StringUtils.isBlank(user.getVcode())) {
            return ResponseJson.failure("验证码错误");
        }

        if (DateUtils.addMinutes(user.getVcodeTime(), 5).before(new Date())) {
            return ResponseJson.failure("验证码过期");
        }

        if (!user.getVcode().equals(param.getVcode())) {
            return ResponseJson.failure("验证码错误");
        }

        user.setName(param.getName());
        user.setSecureKey(RandomUtils.generateId());
        // BCrypt 加密密码
        user.setPassword(passwordEncoder.encode(param.getPassword()));
        user.setUpdateBy(param.getName());
        user.setUpdateTime(new Date());
        user.setStatus(User.STATUS_ACTIVE);
        if (userService.updateById(user)) {
            return ResponseJson.success("注册成功", null);
        } else {
            return ResponseJson.failure("注册失败");
        }
    }
}