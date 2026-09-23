package info.wesite.core.service.impl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.TXTRecord;
import info.wesite.core.entity.DomainDns;
import info.wesite.core.handler.Geoip2Handler;
import info.wesite.core.mapper.DomainDnsMapper;
class DomainDnsRecordWriterTest {
    private final DomainDnsMapper mapper = mock(DomainDnsMapper.class);
    private final Geoip2Handler geo = mock(Geoip2Handler.class);
    private DomainDnsRecordWriter writer;
    private TXTRecord record;
    @BeforeEach void setUp() throws Exception {
        writer = new DomainDnsRecordWriter(mapper, geo);
        record = new TXTRecord(Name.fromString("example.com."), DClass.IN, 300, "Token X ");
    }
    @Test void insertsOriginalTxtBytesAndLiveState() {
        writer.saveObserved("domain", record);
        var c = ArgumentCaptor.forClass(DomainDns.class);
        verify(mapper).insert(c.capture());
        assertEquals(record.rdataToString(), c.getValue().getValue());
        assertEquals(0, c.getValue().getDeleted());
        assertEquals(1, c.getValue().getStatus());
        assertEquals(300, c.getValue().getTtl());
    }
    @Test void refreshesExistingWithoutReplacingCreationOrGeolocation() {
        when(mapper.findExact("domain", "example.com.", "TXT", record.rdataToString(), false)).thenReturn(List.of(existing("original")));
        when(mapper.findExact("domain", "example.com.", "TXT", record.rdataToString(), true)).thenReturn(List.of(existing("original")));
        writer.saveObserved("domain", record);
        verify(mapper, never()).insert(any(DomainDns.class));
        var c = ArgumentCaptor.forClass(DomainDns.class);
        verify(mapper).updateById(c.capture());
        assertEquals("original", c.getValue().getId());
        assertEquals(1, c.getValue().getStatus());
        assertEquals(300, c.getValue().getTtl());
        assertNull(c.getValue().getCreateTime());
        assertNull(c.getValue().getAsnJson());
    }
    @Test void concurrentInsertUsesCurrentLockingReadAndReactivatesWinner() {
        when(mapper.insert(any(DomainDns.class))).thenThrow(new DuplicateKeyException("unique"));
        when(mapper.findExact("domain", "example.com.", "TXT", record.rdataToString(), true)).thenReturn(List.of(existing("winner")));
        writer.saveObserved("domain", record);
        var c = ArgumentCaptor.forClass(DomainDns.class);
        verify(mapper).updateById(c.capture());
        assertEquals("winner", c.getValue().getId());
    }
    @Test void unrelatedKeyOrHashCollisionIsNotSwallowed() {
        var conflict = new DuplicateKeyException("another key or different bytes");
        when(mapper.insert(any(DomainDns.class))).thenThrow(conflict);
        assertSame(conflict, assertThrows(DuplicateKeyException.class, () -> writer.saveObserved("domain", record)));
        verify(mapper, never()).updateById(any(DomainDns.class));
    }
    @Test void legacyDuplicatesAreReportedInsteadOfArbitrarySelection() {
        when(mapper.findExact("domain", "example.com.", "TXT", record.rdataToString(), false)).thenReturn(List.of(existing("one"),existing("two")));
        assertThrows(IllegalStateException.class, () -> writer.saveObserved("domain", record));
        verify(mapper, never()).insert(any(DomainDns.class));
        verify(mapper, never()).updateById(any(DomainDns.class));
    }
    private DomainDns existing(String id) {
        DomainDns d = new DomainDns(); d.setId(id); d.setStatus(2); return d;
    }
}
