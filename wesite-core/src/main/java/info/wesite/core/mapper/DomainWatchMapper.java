package info.wesite.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import info.wesite.core.entity.DomainWatch;
import java.util.Date;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DomainWatchMapper extends BaseMapper<DomainWatch> {

    @Select("SELECT * FROM WEB_DOMAIN_WATCH WHERE ID = #{id} FOR UPDATE")
    DomainWatch selectByIdForUpdate(@Param("id") String id);

    @Update("UPDATE WEB_DOMAIN_WATCH SET SCAN_CLAIM_TOKEN = #{claimToken}, "
        + "SCAN_CLAIM_UNTIL = #{claimUntil} WHERE ID = #{id} AND STATUS = 1 AND DELETED = 0 "
        + "AND (SCAN_CLAIM_TOKEN IS NULL OR SCAN_CLAIM_UNTIL < #{now})")
    int claimScan(
        @Param("id") String id,
        @Param("claimToken") String claimToken,
        @Param("claimUntil") Date claimUntil,
        @Param("now") Date now);

    @Update("UPDATE WEB_DOMAIN_WATCH SET DOMAIN_ID = #{watch.domainId}, REGISTRAR = #{watch.registrar}, "
        + "EXPIRY_DATE_TEXT = #{watch.expiryDateText}, EXPIRY_DATE = #{watch.expiryDate}, "
        + "LAST_CHECK_TIME = #{watch.lastCheckTime}, UPDATE_TIME = #{watch.updateTime}, "
        + "SCAN_CLAIM_TOKEN = NULL, SCAN_CLAIM_UNTIL = NULL "
        + "WHERE ID = #{watch.id} AND SCAN_CLAIM_TOKEN = #{claimToken}")
    int completeScan(
        @Param("watch") DomainWatch watch,
        @Param("claimToken") String claimToken);

    @Update("UPDATE WEB_DOMAIN_WATCH SET SCAN_CLAIM_TOKEN = NULL, SCAN_CLAIM_UNTIL = NULL "
        + "WHERE ID = #{id} AND SCAN_CLAIM_TOKEN = #{claimToken}")
    int releaseScan(@Param("id") String id, @Param("claimToken") String claimToken);
}
