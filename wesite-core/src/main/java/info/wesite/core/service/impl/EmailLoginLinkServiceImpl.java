package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.EmailLoginLink;
import info.wesite.core.mapper.EmailLoginLinkMapper;
import info.wesite.core.service.EmailLoginLinkService;

@Service
public class EmailLoginLinkServiceImpl extends ServiceImpl<EmailLoginLinkMapper, EmailLoginLink>
        implements EmailLoginLinkService {
}
