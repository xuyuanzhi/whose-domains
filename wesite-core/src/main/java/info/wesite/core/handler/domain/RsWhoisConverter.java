package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class RsWhoisConverter implements WhoisConverter {
	
	public static final String Registration_date = "Registration date:";
	public static final String Modification_date = "Modification date:";
	public static final String Expiration_date = "Expiration date:";
	public static final String Domain_status = "Domain status:";
	public static final String Technical_contact = "Technical contact:";
	public static final String DNS = "DNS:";
	public static final String DNSSEC_signed = "DNSSEC signed:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Registrar)) {
				result.setRegistrar(line.replace(Registrar, "").trim());
			} else if (line.startsWith(Modification_date)) {
				result.setRegistUpdateDateText(line.replace(Modification_date, "").trim());
			} else if (line.startsWith(Registration_date)) {
				result.setRegistCreateDateText(line.replace(Registration_date, "").trim());
			} else if (line.startsWith(Expiration_date)) {
				result.setRegistExpiryDateText(line.replace(Expiration_date, "").trim());
			} else if (line.startsWith(Domain_status)) {
				if (result.getDomainStatus() == null) {
					result.setDomainStatus(line.replace(Domain_status, "").trim());
				} else {
					result.setDomainStatus(result.getDomainStatus()  + "," + line.replace(Domain_status, "").trim());
				}
			} else if (line.startsWith(Registrant)) {
				result.setRegistrantOrg(line.replace(Registrant, "").trim());
			} else if (line.startsWith(Registrant_Contact_Email)) {
				result.setRegistrantEmail(line.replace(Registrant_Contact_Email, "").trim());
			} else if (line.startsWith(Technical_contact)) {
				result.setTechName(line.replace(Technical_contact, "").trim());
			} else if (line.startsWith(DNS)) {
				String value = line.replace(DNS, "").trim();
				if (result.getNameServers() == null) {
					result.setNameServers(value);
				} else {
					result.setNameServers(result.getNameServers() + "," + value);
				}
			} else if (line.startsWith(DNSSEC_signed)) {
				result.setDnssec(line.replace(DNSSEC_signed, "").trim());
			}
		}
		br.close();
	}

}
