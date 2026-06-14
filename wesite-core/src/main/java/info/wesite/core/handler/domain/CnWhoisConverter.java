package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class CnWhoisConverter implements WhoisConverter {

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(ROID)) {
				result.setRegistryDomainID(line.replace(ROID, "").trim());
			} else if (line.startsWith(Sponsoring_Registrar)) {
				result.setRegistrar(line.replace(Sponsoring_Registrar, "").trim());
			} else if (line.startsWith(Updated_Date)) {
				result.setRegistUpdateDateText(line.replace(Updated_Date, "").trim());
			} else if (line.startsWith(Registration_Time)) {
				result.setRegistCreateDateText(line.replace(Registration_Time, "").trim());
			} else if (line.startsWith(Expiration_Time)) {
				result.setRegistExpiryDateText(line.replace(Expiration_Time, "").trim());
			} else if (line.startsWith(Domain_Status)) {
				if (result.getDomainStatus() == null) {
					result.setDomainStatus(line.replace(Domain_Status, "").trim());
				} else {
					result.setDomainStatus(result.getDomainStatus()  + "," + line.replace(Domain_Status, "").trim());
				}
			} else if (line.startsWith(Registrant)) {
				result.setRegistrantOrg(line.replace(Registrant, "").trim());
			} else if (line.startsWith(Registrant_Contact_Email)) {
				result.setRegistrantEmail(line.replace(Registrant_Contact_Email, "").trim());
			} else if (line.startsWith(Tech_Name)) {
				result.setTechName(line.replace(Tech_Name, "").trim());
			} else if (line.startsWith(Tech_Email)) {
				result.setTechEmail(line.replace(Tech_Email, "").trim());
			} else if (line.startsWith(Tech_Phone)) {
				result.setTechPhone(line.replace(Tech_Phone, "").trim());
			} else if (line.startsWith(Name_Server)) {
				if (result.getNameServers() == null) {
					result.setNameServers(line.replace(Name_Server, "").trim());
				} else {
					result.setNameServers(result.getNameServers() + "," + line.replace(Name_Server, "").trim());
				}
			} else if (line.startsWith(DNSSEC)) {
				result.setDnssec(line.replace(DNSSEC, "").trim());
			}
		}
		br.close();
	}

}
