package info.wesite.admin.view;

import info.wesite.core.entity.User;

public record AdminSessionView(String id, String name, String phoneNo) {

    public static AdminSessionView from(User user) {
        return new AdminSessionView(user.getId(), user.getName(), user.getPhoneNo());
    }
}
