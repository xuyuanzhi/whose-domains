package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class SkWhoisConverter implements WhoisConverter {

	public static final String Created = "Created:";
	public static final String Valid_Until = "Valid Until:";
	public static final String Updated = "Updated:";
	public static final String Domain_Status = "Domain Status:";
	public static final String Nameserver = "Nameserver:";
	public static final String DNSSEC = "DNSSEC:";
	public static final String Domain_registrant = "Domain registrant:";
	public static final String Registrar = "Registrar:";
	public static final String Technical_Contact = "Technical Contact:";
	public static final String Name = "Name:";
	public static final String Organization = "Organization:";
	public static final String Organization_ID = "Organization ID:";
	public static final String Phone = "Phone:";
	public static final String Email = "Email:";
	public static final String City = "City:";
	public static final String Country_Code = "Country Code:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Created) && result.getRegistCreateDateText() == null) {
				result.setRegistCreateDateText(line.replace(Created, "").trim());
			} else if (line.startsWith(Updated) && result.getRegistUpdateDateText() == null) {
				result.setRegistUpdateDateText(line.replace(Updated, "").trim());
			} else if (line.startsWith(Valid_Until)) {
				result.setRegistExpiryDateText(line.replace(Valid_Until, "").trim());
			} else if (line.startsWith(Domain_Status)) {
				result.setDomainStatus(line.replace(Domain_Status, "").trim());
			} else if (line.startsWith(Nameserver)) {
				String value = line.replace(Nameserver, "").trim();
				if (result.getNameServers() == null) {
					result.setNameServers(value);
				} else {
					result.setNameServers(result.getNameServers() + "," + value);
				}
			} else if (line.startsWith(DNSSEC)) {
				result.setDnssec(line.replace(DNSSEC, "").trim());
			} else if (line.startsWith(Registrar)) {
				String line2 = null;
				while ((line2 = br.readLine()) != null) {
					if (StringUtils.isBlank(line2)) {
						break;
					}
					
					if (line2.startsWith(Organization)) {
						result.setRegistrar(line2.replace(Organization, "").trim());
					} else if (line2.startsWith(Organization_ID)) {
						result.setRegistrarIanaID(line2.replace(Organization_ID, "").trim());
					}
				}
			} else if (line.startsWith(Domain_registrant)) {
				String line2 = null;
				while ((line2 = br.readLine()) != null) {
					if (StringUtils.isBlank(line2)) {
						break;
					}
					
					if (line2.startsWith(Name)) {
						result.setRegistrantName(line2.replace(Name, "").trim());
					} else if (line2.startsWith(Organization)) {
						result.setRegistrantOrg(line2.replace(Organization, "").trim());
					} else if (line2.startsWith(City)) {
						result.setRegistrantCity(line2.replace(City, "").trim());
					} else if (line2.startsWith(Country_Code)) {
						result.setRegistrantCountry(line2.replace(Country_Code, "").trim());
					}
				}
			} else if (line.startsWith(Technical_Contact) && result.getRegistCreateDateText() == null) {
				String line2 = null;
				while ((line2 = br.readLine()) != null) {
					if (StringUtils.isBlank(line2)) {
						break;
					}
					
					if (line2.startsWith(Name)) {
						result.setTechName(line2.replace(Name, "").trim());
					} else if (line2.startsWith(Email)) {
						result.setTechEmail(line2.replace(Email, "").trim());
					} else if (line2.startsWith(Phone)) {
						result.setTechPhone(line2.replace(Phone, "").trim());
					}
				}
			}
		}
		br.close();
	}

}
