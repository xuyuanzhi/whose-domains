package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.DomainWatch;
import info.wesite.core.mapper.DomainWatchMapper;
import info.wesite.core.service.DomainWatchService;

@Service
public class DomainWatchServiceImpl extends ServiceImpl<DomainWatchMapper, DomainWatch> implements DomainWatchService {

}
