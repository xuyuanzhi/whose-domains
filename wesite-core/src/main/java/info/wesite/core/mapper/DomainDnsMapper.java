package info.wesite.core.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.DomainDns;

public interface DomainDnsMapper extends BaseMapper<DomainDns> {

    /** Exact DNS identity; the ordinary DOMAIN_ID predicate retains index access.
     * A locking read after a duplicate-key conflict sees the committed winner even
     * when the caller already has a REPEATABLE READ snapshot. */
    @Select("<script>SELECT * FROM WEB_DOMAIN_DNS WHERE DELETED = 0 " +
        "AND DOMAIN_ID = #{domainId} " +
        "AND CAST(DOMAIN_ID AS BINARY) = CAST(#{domainId} AS BINARY) " +
        "AND CAST(NAME AS BINARY) = CAST(#{name} AS BINARY) " +
        "AND CAST(TYPE AS BINARY) = CAST(#{type} AS BINARY) " +
        "AND CAST(VALUE AS BINARY) = CAST(#{value} AS BINARY) " +
        "LIMIT 2 <if test='locking'>FOR UPDATE</if></script>")
    List<DomainDns> findExact(@Param("domainId") String domainId, @Param("name") String name,
        @Param("type") String type, @Param("value") String value, @Param("locking") boolean locking);


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