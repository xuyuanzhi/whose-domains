package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.NotificationPreference;
import info.wesite.core.mapper.NotificationPreferenceMapper;
import info.wesite.core.service.NotificationPreferenceService;

@Service
public class NotificationPreferenceServiceImpl
        extends ServiceImpl<NotificationPreferenceMapper, NotificationPreference>
        implements NotificationPreferenceService {
}
