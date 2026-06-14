package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class GovWhoisConverter extends ComWhoisConverter {

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		super.fillDomainWithText(result, text);
		
		BufferedReader br = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
		String line = null;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.startsWith(Registry_Expiry_Date)) {
				result.setRegistExpiryDateText(line.replace(Registry_Expiry_Date, "").trim());
			}
		}
		br.close();
	}

}
