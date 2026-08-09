package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.MonitorSnapshot;
import info.wesite.core.mapper.MonitorSnapshotMapper;
import info.wesite.core.service.MonitorSnapshotService;

@Service
public class MonitorSnapshotServiceImpl extends ServiceImpl<MonitorSnapshotMapper, MonitorSnapshot>
        implements MonitorSnapshotService {
}
