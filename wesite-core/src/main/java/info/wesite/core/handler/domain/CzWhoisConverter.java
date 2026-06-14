package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class CzWhoisConverter implements WhoisConverter {

	public static final String registrant = "registrant:";
	public static final String registrar = "registrar:";
	public static final String registered = "registered:";
	public static final String changed = "changed:";
	public static final String expire = "expire:";
	public static final String nserver = "nserver:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (result.getRegistrar() == null && line.startsWith(registrar)) {
				result.setRegistrar(line.replace(registrar, "").trim());
			} else if (line.startsWith(registrant)) {
				result.setRegistrantOrg(line.replace(registrant, "").trim());
			} else if (line.startsWith(registered) && result.getRegistCreateDateText() == null) {
				result.setRegistCreateDateText(line.replace(registered, "").trim());
			} else if (line.startsWith(changed) && result.getRegistUpdateDateText() == null) {
				result.setRegistUpdateDateText(line.replace(changed, "").trim());
			} else if (line.startsWith(expire)) {
				result.setRegistExpiryDateText(line.replace(expire, "").trim());
			} else if (line.startsWith(nserver)) {
				String value = line.replace(nserver, "").trim();
				if (result.getNameServers() == null) {
					result.setNameServers(value);
				} else {
					result.setNameServers(result.getNameServers() + "," + value);
				}
			}
		}
		br.close();
	}

}
