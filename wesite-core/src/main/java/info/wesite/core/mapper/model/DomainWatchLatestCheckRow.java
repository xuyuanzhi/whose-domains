package info.wesite.core.mapper.model;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DomainWatchLatestCheckRow {
    private String watchId;
    private Date lastSuccessfulCheck;
}
