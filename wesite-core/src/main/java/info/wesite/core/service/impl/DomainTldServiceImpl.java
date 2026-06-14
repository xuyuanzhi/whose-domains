package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.DomainTld;
import info.wesite.core.mapper.DomainTldMapper;
import info.wesite.core.service.DomainTldService;

@Service
public class DomainTldServiceImpl extends ServiceImpl<DomainTldMapper, DomainTld> implements DomainTldService {

}
