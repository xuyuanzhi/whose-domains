package info.wesite.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_REGISTRAR")
@Data
public class Registrar extends BaseEntity {

	private String name;
	
	private String ianaId;
	
	
}
