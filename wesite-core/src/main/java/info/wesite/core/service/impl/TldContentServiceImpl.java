package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import info.wesite.core.entity.TldContent;
import info.wesite.core.mapper.TldContentMapper;
import info.wesite.core.service.TldContentService;

@Service
public class TldContentServiceImpl extends ServiceImpl<TldContentMapper, TldContent>
        implements TldContentService {
}
