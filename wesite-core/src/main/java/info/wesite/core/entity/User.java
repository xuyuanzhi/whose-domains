package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("SYS_USER")
@Data
public class User extends BaseEntity {
    
    public static final int STATUS_NEW = 0;
    
    public static final String TYPE_ADMIN = "ADMIN";
    public static final String TYPE_PERSON = "PERSON";

    private String name;
    private String phoneNo;
    private String password;
    private String secureKey;
    private String vcode;
    private Date vcodeTime;
    private String userType;
}
