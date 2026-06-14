package info.wesite.core.config;

import info.wesite.core.entity.User;

public class UserHolder {

	private static final ThreadLocal<User> userLocal = new ThreadLocal<>();
	
	public static User get() {
        return userLocal.get();
    }

    public static void set(User user) {
    	userLocal.set(user);
    }

    public static void remove() {
    	userLocal.remove();
    }
}
