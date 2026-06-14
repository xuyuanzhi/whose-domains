package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class SeWhoisConverter implements WhoisConverter {

	public static final String created = "created:";
	public static final String modified = "modified:";
	public static final String expires = "expires:";
	public static final String nserver = "nserver:";
	public static final String dnssec = "dnssec:";
	public static final String status = "status:";
	public static final String holder = "holder:";
	public static final String registrar = "registrar:";

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(created)) {
				result.setRegistCreateDateText(line.replace(created, "").trim());
			} else if (line.startsWith(modified)) {
				result.setRegistUpdateDateText(line.replace(modified, "").trim());
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
			} else if (line.startsWith(dnssec)) {
				result.setDnssec(line.replace(dnssec, "").trim());
			} else if (line.startsWith(registrar)) {
				result.setRegistrar(line.replace(registrar, "").trim());
			} else if (line.startsWith(holder)) {
				result.setRegistrantOrg(line.replace(holder, "").trim());
			}
		}
		br.close();
	}

}
