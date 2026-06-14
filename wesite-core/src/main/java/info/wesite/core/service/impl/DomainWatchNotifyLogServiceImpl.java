package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.DomainWatchNotifyLog;
import info.wesite.core.mapper.DomainWatchNotifyLogMapper;
import info.wesite.core.service.DomainWatchNotifyLogService;

@Service
public class DomainWatchNotifyLogServiceImpl
        extends ServiceImpl<DomainWatchNotifyLogMapper, DomainWatchNotifyLog>
        implements DomainWatchNotifyLogService {
}
