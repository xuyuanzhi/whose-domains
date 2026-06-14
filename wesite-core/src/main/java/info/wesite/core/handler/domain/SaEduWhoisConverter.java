package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class SaEduWhoisConverter implements WhoisConverter {

	public static final String Registrant = "Registrant:";
	public static final String Technical_Contact = "Technical Contact:";
	public static final String Name_Servers = "Name Servers:";
	public static final String DNSSEC = "DNSSEC:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Registrant)) {
				result.setRegistrantOrg(br.readLine());
			} else if (line.startsWith(Technical_Contact)) {
				result.setTechName(br.readLine());
			} else if (line.startsWith(Name_Servers)) {
				String value = null;
				while ((value = br.readLine()) != null) {
					if (result.getNameServers() == null) {
						result.setNameServers(value);
					} else {
						result.setNameServers(result.getNameServers() + "," + value);
					}
				}
			} else if (line.startsWith(DNSSEC)) {
				result.setDnssec(line.replace(DNSSEC, "").trim());
			}
		}
		br.close();
	}

}
