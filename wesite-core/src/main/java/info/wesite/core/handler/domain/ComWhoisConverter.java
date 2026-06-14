package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class ComWhoisConverter implements WhoisConverter {
	
	public static final String Nameserver = "Nameserver:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Registry_Domain_ID)) {
				result.setRegistryDomainID(line.replace(Registry_Domain_ID, "").trim());
			} else if (line.startsWith(Registrar_URL)) {
				result.setRegistrarUrl(line.replace(Registrar_URL, "").trim());
			} else if (line.startsWith(Registrar)) {
				result.setRegistrar(line.replace(Registrar, "").trim());
			} else if (line.startsWith(Registrar_IANA_ID)) {
				result.setRegistrarIanaID(line.replace(Registrar_IANA_ID, "").trim());
			} else if (line.startsWith(Updated_Date)) {
				result.setRegistUpdateDateText(line.replace(Updated_Date, "").trim());
			} else if (line.startsWith(Creation_Date)) {
				result.setRegistCreateDateText(line.replace(Creation_Date, "").trim());
			} else if (line.startsWith(Registrar_Registration_Expiration_Date)) {
				result.setRegistExpiryDateText(line.replace(Registrar_Registration_Expiration_Date, "").trim());
			} else if (line.startsWith(Registry_Expiry_Date)) {
				result.setRegistExpiryDateText(line.replace(Registry_Expiry_Date, "").trim());
			} else if (line.startsWith(Domain_Status)) {
				if (result.getDomainStatus() == null) {
					result.setDomainStatus(line.replace(Domain_Status, "").trim());
				} else {
					result.setDomainStatus(result.getDomainStatus()  + "," + line.replace(Domain_Status, "").trim());
				}
			} else if (line.startsWith(Registrant_Organization)) {
				result.setRegistrantOrg(line.replace(Registrant_Organization, "").trim());
			} else if (line.startsWith(Registrant_Name)) {
				result.setRegistrantName(line.replace(Registrant_Name, "").trim());
			} else if (line.startsWith(Registrant_City)) {
				result.setRegistrantCity(line.replace(Registrant_City, "").trim());
			} else if (line.startsWith(Registrant_State_Province)) {
				result.setRegistrantState(line.replace(Registrant_State_Province, "").trim());
			} else if (line.startsWith(Registrant_Country)) {
				result.setRegistrantCountry(line.replace(Registrant_Country, "").trim());
			} else if (line.startsWith(Registrant_Phone)) {
				result.setRegistrantPhone(line.replace(Registrant_Phone, "").trim());
			} else if (line.startsWith(Registrant_Email)) {
				result.setRegistrantEmail(line.replace(Registrant_Email, "").trim());
			} else if (line.startsWith(Tech_Name)) {
				result.setTechName(line.replace(Tech_Name, "").trim());
			} else if (line.startsWith(Tech_Email)) {
				result.setTechEmail(line.replace(Tech_Email, "").trim());
			} else if (line.startsWith(Tech_Phone)) {
				result.setTechPhone(line.replace(Tech_Phone, "").trim());
			} else if (line.startsWith(Name_Server) || line.startsWith(Nameserver)) {
				String value = line.replace(Name_Server, "").replace(Nameserver, "").trim();
				if (result.getNameServers() == null) {
					result.setNameServers(value);
				} else {
					result.setNameServers(result.getNameServers() + "," + value);
				}
			} else if (line.startsWith(DNSSEC)) {
				result.setDnssec(line.replace(DNSSEC, "").trim());
			}
		}
		br.close();
	}

}
