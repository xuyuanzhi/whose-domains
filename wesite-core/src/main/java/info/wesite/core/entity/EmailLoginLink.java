package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** A hashed, single-use login link sent to an email address. */
@TableName("WEB_EMAIL_LOGIN_LINK")
@Data
public class EmailLoginLink extends BaseEntity {

    private String email;
    private String tokenHash;
    private String userId;
    private Date expiresAt;
    private Date consumedAt;
    private String redirectPath;
}
