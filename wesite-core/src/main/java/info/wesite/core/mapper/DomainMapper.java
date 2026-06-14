package info.wesite.core.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.Domain;

public interface DomainMapper extends BaseMapper<Domain> {

    /**
     * 查询即将过期域名（只取必要字段，避免加载 whoisText/rdapText 等大字段）
     * 按 REGIST_EXPIRY_DATE_TEXT 字符串范围 + 状态过滤，offset/limit 直接分页（不做 COUNT）
     */
    @Select("SELECT ID, NAME, TLD_NAME, SLD_NAME, REGISTRAR, REGISTRANT_ORG, REGISTRANT_COUNTRY, " +
            "REGIST_CREATE_DATE_TEXT, REGIST_EXPIRY_DATE_TEXT, STATUS " +
            "FROM WEB_DOMAIN " +
            "WHERE DELETED = 0 AND STATUS = 1 " +
            "AND REGIST_EXPIRY_DATE_TEXT IS NOT NULL " +
            "AND REGIST_EXPIRY_DATE_TEXT >= #{nowStr} " +
            "AND REGIST_EXPIRY_DATE_TEXT <= #{futureStr} " +
            "ORDER BY REGIST_EXPIRY_DATE_TEXT ASC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<Domain> selectExpiringDomains(@Param("nowStr") String nowStr,
                                       @Param("futureStr") String futureStr,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * 查询即将过期域名总数（用于首次预热缓存时获取 total）
     */
    @Select("SELECT COUNT(*) FROM WEB_DOMAIN " +
            "WHERE DELETED = 0 AND STATUS = 1 " +
            "AND REGIST_EXPIRY_DATE_TEXT IS NOT NULL " +
            "AND REGIST_EXPIRY_DATE_TEXT >= #{nowStr} " +
            "AND REGIST_EXPIRY_DATE_TEXT <= #{futureStr}")
    long countExpiringDomains(@Param("nowStr") String nowStr,
                              @Param("futureStr") String futureStr);
}
