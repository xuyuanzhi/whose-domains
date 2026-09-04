package info.wesite.admin.view;

import info.wesite.core.entity.User;

public record AdminUserView(
        String id,
        String name,
        String phoneNo,
        Integer status,
        String statusText,
        String createTimeText,
        String updateTimeText) {

    public static AdminUserView from(User user) {
        return new AdminUserView(
            user.getId(),
            user.getName(),
            user.getPhoneNo(),
            user.getStatus(),
            user.getStatusText(),
            user.getCreateTimeText(),
            user.getUpdateTimeText());
    }
}
