package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import info.wesite.core.entity.ContactInfo;
import info.wesite.core.mapper.ContactInfoMapper;
import info.wesite.core.service.ContactInfoService;

@Service
public class ContactInfoServiceImpl extends ServiceImpl<ContactInfoMapper, ContactInfo> implements ContactInfoService {

}
