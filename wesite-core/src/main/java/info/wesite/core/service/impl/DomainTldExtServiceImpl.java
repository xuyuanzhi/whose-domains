package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.DomainTldExt;
import info.wesite.core.mapper.DomainTldExtMapper;
import info.wesite.core.service.DomainTldExtService;

@Service
public class DomainTldExtServiceImpl extends ServiceImpl<DomainTldExtMapper, DomainTldExt> implements DomainTldExtService {

}
