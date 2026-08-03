package info.wesite.core.service.impl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import info.wesite.core.entity.ApiKey;
import info.wesite.core.mapper.ApiKeyMapper;
import info.wesite.core.service.ApiKeyService;
@Service
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKey> implements ApiKeyService { }
