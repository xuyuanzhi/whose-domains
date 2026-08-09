package info.wesite.core.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("WEB_USER_NOTIFICATION")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserNotification extends BaseEntity {

    private String userId;

    private String eventId;

    private String title;

    private String content;

    private String targetPath;

    private Date readAt;

    private String emailState;

    private Date emailedAt;
}
