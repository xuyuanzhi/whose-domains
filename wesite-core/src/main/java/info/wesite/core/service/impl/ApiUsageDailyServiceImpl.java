package info.wesite.core.service.impl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import info.wesite.core.entity.ApiUsageDaily;
import info.wesite.core.mapper.ApiUsageDailyMapper;
import info.wesite.core.service.ApiUsageDailyService;
@Service public class ApiUsageDailyServiceImpl extends ServiceImpl<ApiUsageDailyMapper, ApiUsageDaily> implements ApiUsageDailyService { }
