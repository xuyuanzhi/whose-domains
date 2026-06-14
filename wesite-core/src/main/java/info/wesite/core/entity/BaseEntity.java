package info.wesite.core.entity;

import java.io.Serializable;
import java.util.Date;

import org.apache.commons.lang3.time.DateFormatUtils;

import com.baomidou.mybatisplus.annotation.TableLogic;

public class BaseEntity implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public static final int STATUS_ACTIVE = 1;
	public static final int STATUS_INACTIVE = 2;

	private String id;

	private Integer status;

	@TableLogic(value = "0", delval = "1")
	private Integer deleted;

	private String createBy;

	private Date createTime;

	private String updateBy;

	private Date updateTime;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Integer getDeleted() {
		return deleted;
	}

	public void setDeleted(Integer deleted) {
		this.deleted = deleted;
	}

	public String getCreateBy() {
		return createBy;
	}

	public void setCreateBy(String createBy) {
		this.createBy = createBy;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}

	public String getUpdateBy() {
		return updateBy;
	}

	public void setUpdateBy(String updateBy) {
		this.updateBy = updateBy;
	}

	public Date getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(Date updateTime) {
		this.updateTime = updateTime;
	}

	public String getCreateTimeText() {
		if (createTime != null) {
			return DateFormatUtils.format(createTime, "yyyy-MM-dd HH:mm:ss");
		} else {
			return "";
		}
	}

	public String getUpdateTimeText() {
		if (updateTime != null) {
			return DateFormatUtils.format(updateTime, "yyyy-MM-dd HH:mm:ss");
		} else {
			return "";
		}
	}
	
	public String getLastOperateTimeText() {
		if (updateTime != null) {
			return DateFormatUtils.format(updateTime, "yyyy-MM-dd HH:mm:ss");
		} else if (createTime != null) {
			return DateFormatUtils.format(createTime, "yyyy-MM-dd HH:mm:ss");
		} else {
			return "";
		}
	}
	
	public String getLastOperateDateText() {
		if (updateTime != null) {
			return DateFormatUtils.format(updateTime, "yyyy-MM-dd");
		} else if (createTime != null) {
			return DateFormatUtils.format(createTime, "yyyy-MM-dd");
		} else {
			return "";
		}
	}

	public String getStatusText() {
		if (getStatus() == null) {
			return "";
		} else if (STATUS_ACTIVE == getStatus()) {
			return "启用";
		} else {
			return "禁用";
		}
	}
}
