package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class JpWhoisConverter implements WhoisConverter {
	
	public static final String Name_Server = "[Name Server]";
	public static final String Registrant = "[Registrant]";
	public static final String Registration_Date = "[登録年月日]";
	public static final String Expiration_Date = "[有効期限]";
	public static final String Last_Updated = "[最終更新]";
	public static final String Status = "[状態]";
	public static final String Domain_Status = "[ロック状態]";
	public static final String Name = "[Name]";
	public static final String Email = "[Email]";

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
			} else if (line.startsWith(Registrar_Name)) {
				result.setRegistrar(line.replace(Registrar_Name, "").trim());
			} else if (line.startsWith(Domain_Status)) {
				if (result.getDomainStatus() == null) {
					result.setDomainStatus(line.replace(Domain_Status, "").trim());
				} else {
					result.setDomainStatus(result.getDomainStatus()  + "," + line.replace(Domain_Status, "").trim());
				}
			} else if (line.startsWith(Name_Server)) {
				if (result.getNameServers() == null) {
					result.setNameServers(line.replace(Name_Server, "").trim());
				} else {
					result.setNameServers(result.getNameServers()  + "," + line.replace(Name_Server, "").trim());
				}
			} else if (line.startsWith(Name)) {
				result.setRegistrantName(line.replace(Name, "").trim());
			} else if (line.startsWith(Email)) {
				result.setRegistrantEmail(line.replace(Email, "").trim());
			} else if (line.startsWith(Registrant)) {
				result.setRegistrantOrg(line.replace(Registrant, "").trim());
			}
		}
		br.close();
	}

}
