package info.wesite.core.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.DomainDns;

public interface DomainDnsMapper extends BaseMapper<DomainDns> {

    /**
     * 统计托管在指定IP上的域名数量（去重）
     * 使用子查询避免 COUNT(DISTINCT)，配合覆盖索引性能更优
     * 需要索引: CREATE INDEX idx_dns_value_status_type_domainid ON WEB_DOMAIN_DNS (VALUE(100), STATUS, TYPE, DOMAIN_ID);
     */
    @Select("SELECT COUNT(*) FROM (" +
            "SELECT DISTINCT DOMAIN_ID FROM WEB_DOMAIN_DNS " +
            "WHERE VALUE = #{ip} AND STATUS = #{status} AND TYPE IN ('A', 'AAAA')" +
            ") t")
    long countDistinctDomainIdsByIp(@Param("ip") String ip, @Param("status") int status);

    /**
     * 分页获取托管在指定IP上的域名ID列表（去重）
     * 需要索引: CREATE INDEX idx_dns_value_status_type_domainid ON WEB_DOMAIN_DNS (VALUE(100), STATUS, TYPE, DOMAIN_ID);
     */
    @Select("SELECT DISTINCT DOMAIN_ID FROM WEB_DOMAIN_DNS " +
            "WHERE VALUE = #{ip} AND STATUS = #{status} AND TYPE IN ('A', 'AAAA') " +
            "LIMIT #{offset}, #{pageSize}")
    List<String> selectDistinctDomainIdsByIp(@Param("ip") String ip,
                                              @Param("status") int status,
                                              @Param("offset") long offset,
                                              @Param("pageSize") int pageSize);
}