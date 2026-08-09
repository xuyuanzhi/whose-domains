package info.wesite.core.mapper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DomainWatchLatestEventRow {
    private String watchId;
    private String latestEventRisk;
    private String latestEventType;
    private String latestEventValue;
}
