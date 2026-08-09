package info.wesite.core.mapper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DomainWatchUnreadCountRow {
    private String watchId;
    private long unreadCount;
}
