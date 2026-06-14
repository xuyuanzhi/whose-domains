package info.wesite.core.service;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.extension.service.IService;

import info.wesite.core.entity.Domain;

public interface DomainService extends IService<Domain> {

    /**
     * 查询即将过期的域名（只含必要字段，高性能）
     *
     * @param nowStr    当前日期字符串 yyyy-MM-dd
     * @param futureStr 截止日期字符串 yyyy-MM-dd
     * @param page      页码（1起）
     * @param pageSize  每页条数
     * @return 轻量域名数据列表
     */
    List<Domain> listExpiringDomains(String nowStr, String futureStr, int page, int pageSize);

    /**
     * 查询即将过期域名总数
     */
    long countExpiringDomains(String nowStr, String futureStr);

    /**
     * 预热/刷新指定天数的过期域名缓存（所有分页）
     *
     * @param days 未来几天内过期
     * @return 预热的分页数据 Map（key: page, value: 页数据）
     */
    Map<Integer, Map<String, Object>> buildExpiringDomainsCache(int days);
}
