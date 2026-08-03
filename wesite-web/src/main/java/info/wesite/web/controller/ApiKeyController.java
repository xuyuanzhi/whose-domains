package info.wesite.web.controller;

import java.util.Date;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import info.wesite.core.config.AccessControl;
import info.wesite.core.config.UserHolder;
import info.wesite.core.entity.ApiKey;
import info.wesite.core.service.ApiKeyService;
import info.wesite.core.utils.ApiKeyUtils;
import info.wesite.core.utils.RandomUtils;
import info.wesite.core.view.ResponseJson;

@RestController
@RequestMapping("/user/api-keys")
@AccessControl(level = AccessControl.Level.SESSION)
public class ApiKeyController {
    private final ApiKeyService apiKeyService;
    public ApiKeyController(ApiKeyService apiKeyService) { this.apiKeyService = apiKeyService; }

    @GetMapping("/list")
    public ResponseJson<Map<String, Object>> list() {
        java.util.List<Map<String, Object>> keys = apiKeyService.list(new QueryWrapper<ApiKey>()
                .eq("USER_ID", UserHolder.get().getId()).isNull("REVOKED_AT").orderByDesc("CREATE_TIME"))
                .stream().map(key -> { Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", key.getId()); item.put("name", key.getName()); item.put("prefix", key.getKeyPrefix());
                    item.put("createdAt", key.getCreateTime()); item.put("lastUsedAt", key.getLastUsedAt()); return item; })
                .toList();
        return ResponseJson.success("success", Map.of("items", keys));
    }

    @PostMapping
    public ResponseJson<Map<String, String>> create(@RequestBody Map<String, String> param) {
        String userId = UserHolder.get().getId();
        String name = StringUtils.defaultIfBlank(param.get("name"), "Default key").trim();
        if (name.length() > 100) return ResponseJson.failure("Key name is too long.");
        synchronized (("api-key:" + userId).intern()) {
            long count = apiKeyService.count(new QueryWrapper<ApiKey>().eq("USER_ID", userId).isNull("REVOKED_AT"));
            if (count >= 3) return ResponseJson.failure("Free accounts can create up to 3 active API keys.");
            String plainKey = ApiKeyUtils.generate();
            ApiKey key = new ApiKey();
            key.setId(RandomUtils.generateId()); key.setUserId(userId); key.setName(name);
            key.setKeyPrefix(ApiKeyUtils.prefix(plainKey)); key.setKeyHash(ApiKeyUtils.hash(plainKey));
            key.setCreateBy(userId); key.setCreateTime(new Date()); apiKeyService.save(key);
            return ResponseJson.success(Map.of("key", plainKey, "prefix", key.getKeyPrefix()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseJson<Void> revoke(@PathVariable String id) {
        ApiKey key = apiKeyService.getOne(new QueryWrapper<ApiKey>().eq("ID", id).eq("USER_ID", UserHolder.get().getId()).isNull("REVOKED_AT"));
        if (key == null) return ResponseJson.failure("API key not found.");
        key.setRevokedAt(new Date()); key.setUpdateBy(UserHolder.get().getId()); key.setUpdateTime(new Date());
        apiKeyService.updateById(key);
        return ResponseJson.success();
    }
}
