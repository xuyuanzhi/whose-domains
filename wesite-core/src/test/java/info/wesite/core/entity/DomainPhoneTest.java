package info.wesite.core.entity;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import info.wesite.core.handler.domain.ComWhoisConverter;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.RdapUtils;

class DomainPhoneTest {
    private static final String NOTICE="Personal data, can not be publicly disclosed according to applicable laws.";

    @Test void riwMoscowWhoisDoesNotStorePrivacyNoticeAsPhone() throws Exception {
        String raw="Domain Name: riw.moscow\nRegistrant Phone: "+NOTICE+"\nTech Phone: "+NOTICE+"\n";
        Domain d=new Domain(); d.setName("riw.moscow"); d.setParentWhoisText(raw);
        new ComWhoisConverter().fillDomainWithText(d,raw);
        assertEquals("",d.getRegistrantPhone()); assertEquals("",d.getTechPhone());
        assertEquals(raw,d.getParentWhoisText());
    }
    @Test void rdapRedactionIsHandledWithoutChangingRawResponse() {
        String raw="""
            {"entities":[
              {"roles":["registrant"],"vcardArray":["vcard",[["tel",{},"text","REDACTED FOR PRIVACY"]]]},
              {"roles":["technical"],"vcardArray":["vcard",[["tel",{},"text","Personal data, can not be publicly disclosed according to applicable laws."]]]}
            ],"events":[],"nameservers":[]}
            """;
        Domain d=new Domain(); d.setName("riw.moscow"); d.setParentRdapText(raw);
        assertTrue(RdapUtils.fillRdapInfoFromText(d,raw));
        assertEquals("",d.getRegistrantPhone()); assertEquals("",d.getTechPhone());
        assertEquals(raw,d.getParentRdapText());
    }
    @ParameterizedTest
    @ValueSource(strings={"REDACTED FOR PRIVACY","Not Disclosed","Data Protected","N/A","", "   "})
    void unavailableValuesAreExplicitlyEmpty(String value) {
        Domain d=new Domain(); d.setRegistrantPhone(value); d.setTechPhone(value);
        assertEquals("",d.getRegistrantPhone()); assertEquals("",d.getTechPhone());
    }
    @Test void validFormattingAndExtensionsArePreserved() {
        Domain d=new Domain();
        String phone="tel:+7-495-123-4567;ext=1234";
        d.setRegistrantPhone("  "+phone+"  "); d.setTechPhone("+1 (555) 123-4567 x42");
        assertEquals(phone,d.getRegistrantPhone()); assertEquals("+1 (555) 123-4567 x42",d.getTechPhone());
    }
    @Test void storageLimitsAreEnforcedWithoutTruncatingPhoneNumbers() {
        Domain d=new Domain();
        d.setRegistrantPhone("1".repeat(50)); assertEquals(50,d.getRegistrantPhone().length());
        d.setRegistrantPhone("1".repeat(51)); assertEquals("",d.getRegistrantPhone());
        d.setTechPhone("1".repeat(200)); assertEquals(200,d.getTechPhone().length());
        d.setTechPhone("1".repeat(201)); assertEquals("",d.getTechPhone());
    }
    @Test void missingPhonePreservesOldValueButRedactionClearsItDuringMerge() {
        Domain target=new Domain(); target.setRegistrantPhone("+1234"); target.setTechPhone("+5678");
        Domain incoming=new Domain(); incoming.setRegistrantPhone(null); incoming.setTechPhone(null);
        ReflectionTestUtils.invokeMethod(DomainUtils.class,"copyNonNullDomainFields",incoming,target);
        assertEquals("+1234",target.getRegistrantPhone()); assertEquals("+5678",target.getTechPhone());
        incoming.setRegistrantPhone(NOTICE); incoming.setTechPhone(NOTICE);
        ReflectionTestUtils.invokeMethod(DomainUtils.class,"copyNonNullDomainFields",incoming,target);
        assertEquals("",target.getRegistrantPhone()); assertEquals("",target.getTechPhone());
    }
    @Test void updateSqlClearsRedactedPhonesButSkipsMissingPhones() throws Exception {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(info.wesite.core.mapper.DomainMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        factory.setConfiguration(config);
        var sessionFactory = factory.getObject();
        var statement = sessionFactory.getConfiguration().getMappedStatement(
                "info.wesite.core.mapper.DomainMapper.updateById");
        Domain d = new Domain();
        d.setId("test-phone");
        var parameters = java.util.Map.of("et", d);
        var missing = statement.getBoundSql(parameters);
        assertFalse(missing.getSql().toLowerCase(java.util.Locale.ROOT).contains("registrant_phone="));
        assertFalse(missing.getSql().toLowerCase(java.util.Locale.ROOT).contains("tech_phone="));
        d.setRegistrantPhone(NOTICE);
        d.setTechPhone(NOTICE);
        var cleared = statement.getBoundSql(parameters);
        assertTrue(cleared.getSql().toLowerCase(java.util.Locale.ROOT).contains("registrant_phone="));
        assertTrue(cleared.getSql().toLowerCase(java.util.Locale.ROOT).contains("tech_phone="));
        var meta = sessionFactory.getConfiguration().newMetaObject(parameters);
        for (String property : java.util.List.of("et.registrantPhone", "et.techPhone")) {
            assertTrue(cleared.getParameterMappings().stream().anyMatch(p -> p.getProperty().equals(property)));
            assertEquals("", meta.getValue(property));
        }
    }
}