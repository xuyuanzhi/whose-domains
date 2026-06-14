package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.mapper.UserQueryHistoryMapper;
import info.wesite.core.service.UserQueryHistoryService;

@Service
public class UserQueryHistoryServiceImpl extends ServiceImpl<UserQueryHistoryMapper, UserQueryHistory>
        implements UserQueryHistoryService {
}
