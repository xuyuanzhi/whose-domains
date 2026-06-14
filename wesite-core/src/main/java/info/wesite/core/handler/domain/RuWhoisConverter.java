package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class RuWhoisConverter implements WhoisConverter {
	
	public static final String Name_Server = "nserver:";
	public static final String Registrant = "person:";
	public static final String Registrant_Org = "org:";
	public static final String Registrar = "registrar:";
	public static final String Registrar_URL = "admin-contact:";
	public static final String Registration_Date = "created:";
	public static final String Expiration_Date = "paid-till:";
	public static final String Last_Updated = "Last updated on";
	public static final String Domain_Status = "state:";
	public static final String Source = "source:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Registration_Date)) {
				result.setRegistCreateDateText(line.replace(Registration_Date, "").trim());
			} else if (line.startsWith(Expiration_Date)) {
				result.setRegistExpiryDateText(line.replace(Expiration_Date, "").trim());
			} else if (line.startsWith(Last_Updated)) {
				result.setRegistUpdateDateText(line.replace(Last_Updated, "").trim());
			} else if (line.startsWith(Registrar)) {
				result.setRegistrar(line.replace(Registrar, "").trim());
			} else if (line.startsWith(Registrar_URL)) {
				result.setRegistrarUrl(line.replace(Registrar_URL, "").trim());
			} else if (line.startsWith(Domain_Status)) {
				result.setDomainStatus(line.replace(Domain_Status, "").trim());
			} else if (line.startsWith(Name_Server)) {
				if (result.getNameServers() == null) {
					result.setNameServers(line.replace(Name_Server, "").trim());
				} else {
					result.setNameServers(result.getNameServers()  + "," + line.replace(Name_Server, "").trim());
				}
			} else if (line.startsWith(Registrant)) {
				result.setRegistrantName(line.replace(Registrant, "").trim());
			} else if (line.startsWith(Registrant_Org)) {
				result.setRegistrantOrg(line.replace(Registrant_Org, "").trim());
			}
		}
		br.close();
	}

}
