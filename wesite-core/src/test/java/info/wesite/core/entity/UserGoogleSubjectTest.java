package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserGoogleSubjectTest {

    @Test
    void storesGoogleSubject() {
        User user = new User();
        user.setGoogleSub("10769150350006150715113082367");
        assertEquals("10769150350006150715113082367", user.getGoogleSub());
    }
}
