package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class AuWhoisConverter extends ComWhoisConverter {
	
	public static final String Registrant_Contact_Name = "Registrant Contact Name:";
	public static final String Tech_Contact_Name = "Tech Contact Name:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		super.fillDomainWithText(result, text);
		
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Last_Modified)) {
				result.setRegistUpdateDateText(line.replace(Last_Modified, "").trim());
			} else if (line.startsWith(Registrar_Name)) {
				result.setRegistrar(line.replace(Registrar_Name, "").trim());
			} else if (line.startsWith(Status)) {
				if (result.getDomainStatus() == null) {
					result.setDomainStatus(line.replace(Status, "").trim());
				} else {
					result.setDomainStatus(result.getDomainStatus()  + "," + line.replace(Status, "").trim());
				}
			} else if (line.startsWith(Registrant_Contact_Name)) {
				result.setRegistrantName(line.replace(Registrant_Contact_Name, "").trim());
			} else if (line.startsWith(Tech_Contact_Name)) {
				result.setTechName(line.replace(Tech_Contact_Name, "").trim());
			} else if (line.startsWith(Registrant)) {
				result.setRegistrantOrg(line.replace(Registrant, "").trim());
			}
		}
		br.close();
	}

}
