package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_CONTACT_INFO")
@Data
public class ContactInfo extends BaseEntity {

	private String name;
	
	private String email;
	
	private String subject;
	
	private String message;
	
	private String requestIp;
}
