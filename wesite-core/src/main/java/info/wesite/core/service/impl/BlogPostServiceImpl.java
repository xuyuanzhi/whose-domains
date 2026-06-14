package info.wesite.core.service.impl;

import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import info.wesite.core.entity.BlogPost;
import info.wesite.core.mapper.BlogPostMapper;
import info.wesite.core.service.BlogPostService;

@Service
public class BlogPostServiceImpl extends ServiceImpl<BlogPostMapper, BlogPost>
        implements BlogPostService {
}
