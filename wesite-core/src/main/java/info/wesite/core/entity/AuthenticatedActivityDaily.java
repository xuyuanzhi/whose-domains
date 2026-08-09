package info.wesite.core.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** Minimal, day-granularity fact that an authenticated user was active. */
@TableName("WEB_AUTHENTICATED_ACTIVITY_DAILY")
@Data
public class AuthenticatedActivityDaily implements Serializable {

    @TableId
    private String id;

    private String userId;

    private LocalDate activityDate;

    private Date createTime;
}
