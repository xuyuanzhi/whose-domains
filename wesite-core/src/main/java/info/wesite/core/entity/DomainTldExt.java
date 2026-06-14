package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_DOMAIN_TLD_EXT")
@Data
public class DomainTldExt extends BaseEntity {

	private String name;
	private String dotName;
	private String tldName;
	private String countryName;
	private String note;

}
