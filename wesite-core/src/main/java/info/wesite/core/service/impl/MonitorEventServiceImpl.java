package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.MonitorEvent;
import info.wesite.core.mapper.MonitorEventMapper;
import info.wesite.core.service.MonitorEventService;

@Service
public class MonitorEventServiceImpl extends ServiceImpl<MonitorEventMapper, MonitorEvent>
        implements MonitorEventService {
}
