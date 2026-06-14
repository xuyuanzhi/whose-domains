package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class BrWhoisConverter implements WhoisConverter {
	
	public static final String owner = "owner:";
	public static final String ownerid = "ownerid:";
	public static final String responsible = "responsible:";
	public static final String nserver = "nserver:";
	public static final String created = "created:";
	public static final String changed = "changed:";
	public static final String expires = "expires:";
	public static final String status = "status:";
	public static final String owner_c = "owner-c:";
	public static final String tech_c = "tech-c:";
	
	public static final String nic_hdl_br = "nic-hdl-br:";
	public static final String person = "person:";
	public static final String e_mail = "e-mail:";
	public static final String country = "country:";
//	public static final String created = "created:";
//	public static final String changed = "changed:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		String ownerContactId = null;
		String techContactId = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(owner)) {
				result.setRegistrantOrg(line.replace(owner, "").trim());
			} else if (line.startsWith(created) && result.getRegistCreateDateText() == null) {
				result.setRegistCreateDateText(line.replace(created, "").trim());
			} else if (line.startsWith(changed) && result.getRegistUpdateDateText() == null) {
				result.setRegistUpdateDateText(line.replace(changed, "").trim());
			} else if (line.startsWith(expires)) {
				result.setRegistExpiryDateText(line.replace(expires, "").trim());
			} else if (line.startsWith(status)) {
				result.setDomainStatus(line.replace(status, "").trim());
			} else if (line.startsWith(nserver)) {
				String value = line.replace(nserver, "").trim();
				if (result.getNameServers() == null) {
					result.setNameServers(value);
				} else {
					result.setNameServers(result.getNameServers() + "," + value);
				}
			} else if (line.startsWith(owner_c)) {
				ownerContactId = line.replace(owner_c, "").trim();
			} else if (line.startsWith(tech_c)) {
				techContactId = line.replace(tech_c, "").trim();
			} else if (line.startsWith(nic_hdl_br)) {
				String value = line.replace(nic_hdl_br, "").trim();
				String personText = br.readLine().replace(person, "").trim();
				String emailText = br.readLine().replace(e_mail, "").trim();
				String countryText = br.readLine().replace(country, "").trim();
				
				if (value.equals(ownerContactId)) {
					result.setRegistrantName(personText);
					result.setRegistrantEmail(emailText);
					result.setRegistrantCountry(countryText);
				}
				
				if (value.equals(techContactId)) {
					result.setTechName(personText);
					result.setTechEmail(emailText);
				}
			}
		}
		
		result.setRegistrar("Registro.br");
		result.setRegistrarIanaID(null);
		result.setRegistrarUrl("https://registro.br/");
		
		br.close();
	}

}
