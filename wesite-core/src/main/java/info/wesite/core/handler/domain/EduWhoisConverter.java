package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class EduWhoisConverter implements WhoisConverter {

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Registrant)) {//
				result.setRegistrantOrg(br.readLine().trim());
				br.readLine();
				result.setRegistrantState(br.readLine().trim());
				result.setRegistrantCity(br.readLine().trim());
				result.setRegistrantCountry(br.readLine().trim());
			} else if (line.startsWith(Administrative_Contact)) {
				result.setRegistrantName(br.readLine().trim());
				br.readLine();
				br.readLine();
				br.readLine();
				br.readLine();
				result.setRegistrantPhone(br.readLine().trim());
				result.setRegistrantEmail(br.readLine().trim());
			} else if (line.startsWith(Technical_Contact)) {
				result.setTechName(br.readLine().trim());
				br.readLine();
				br.readLine();
				br.readLine();
				br.readLine();
				br.readLine();
				result.setTechPhone(br.readLine().trim());
				result.setTechEmail(br.readLine().trim());
			} else if (line.startsWith(Name_Servers)) {
				while (true) {
					String server = br.readLine().trim();
					if (StringUtils.isBlank(server)) {
						break;
					}
					
					if (result.getNameServers() == null) {
						result.setNameServers(server);
					} else {
						result.setNameServers(result.getNameServers() + "," + server);
					}
				}
			} else if (line.startsWith(Domain_record_activated)) {
				result.setRegistCreateDateText(line.replace(Domain_record_activated, "").trim());
			} else if (line.startsWith(Domain_record_last_updated)) {
				result.setRegistUpdateDateText(line.replace(Domain_record_last_updated, "").trim());
			} else if (line.startsWith(Domain_expires)) {
				result.setRegistExpiryDateText(line.replace(Domain_expires, "").trim());
			}
			
			result.setRegistrar("EDUCAUSE");
			result.setRegistrarUrl("https://net.educause.edu/");
		}
		br.close();
	}

}
