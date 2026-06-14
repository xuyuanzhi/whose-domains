package info.wesite.core.handler.domain;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import info.wesite.core.entity.Domain;

@Component
public class UaWhoisConverter extends ComWhoisConverter {

	@Override
	public void fillDomainWithText(Domain result, String text) throws IOException {
		List<String> lines = new ArrayList<>();

		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(text.getBytes("utf-8"))));
			String line = null;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				lines.add(line);
			}

			// Registry Domain ID
			Optional<String> licenseOp = lines.stream().filter(item -> item.startsWith("license:"))
					.map(item -> item.replace("license:", "").trim()).findFirst();
			if (licenseOp.isPresent()) {
				result.setRegistryDomainID(licenseOp.get());
			}

			// Name Servers
			String ns = lines.stream().filter(item -> item.startsWith("nserver:"))
					.map(item -> item.replace("nserver:", "").trim()).collect(Collectors.joining(","));
			if (StringUtils.isNotBlank(ns)) {
				result.setNameServers(ns);
			}

			// Domain Status
			Optional<String> statusOp = lines.stream().filter(item -> item.startsWith("status:"))
					.map(item -> item.replace("status:", "").trim()).findFirst();
			if (statusOp.isPresent()) {
				result.setDomainStatus(statusOp.get());
			}

			// Date
			Optional<String> createdOp = lines.stream().filter(item -> item.startsWith("created:"))
					.map(item -> item.replace("created:", "").trim()).findFirst();
			if (createdOp.isPresent()) {
				result.setRegistCreateDateText(createdOp.get());
			}

			Optional<String> modifiedOp = lines.stream().filter(item -> item.startsWith("modified:"))
					.map(item -> item.replace("modified:", "").trim()).findFirst();
			if (modifiedOp.isPresent()) {
				result.setRegistUpdateDateText(modifiedOp.get());
			}

			Optional<String> expiresOp = lines.stream().filter(item -> item.startsWith("expires:"))
					.map(item -> item.replace("expires:", "").trim()).findFirst();
			if (expiresOp.isPresent()) {
				result.setRegistExpiryDateText(expiresOp.get());
			}

			// registrar
			if (lines.contains("% Registrar:")) {
				int index = lines.indexOf("% Registrar:");
				List<String> subLines = new ArrayList<>();
				for (int i = index; i < lines.size(); i++) {
					String s = lines.get(i);
					if (StringUtils.isBlank(s)) {
						break;
					} else {
						subLines.add(s);
					}
				}

				//
				Optional<String> registrarOp = subLines.stream().filter(item -> item.startsWith("organization:"))
						.map(item -> item.replace("organization:", "").trim()).findFirst();
				if (registrarOp.isPresent()) {
					result.setRegistrar(registrarOp.get());
				}

				Optional<String> urlOp = subLines.stream().filter(item -> item.startsWith("url:"))
						.map(item -> item.replace("url:", "").trim()).findFirst();
				if (urlOp.isPresent()) {
					result.setRegistrarUrl(urlOp.get());
				}
			}

			// registrant
			if (lines.contains("% Registrant:")) {
				int index = lines.indexOf("% Registrant:");
				List<String> subLines = new ArrayList<>();
				for (int i = index; i < lines.size(); i++) {
					String s = lines.get(i);
					if (StringUtils.isBlank(s)) {
						break;
					} else {
						subLines.add(s);
					}
				}

				// name
				Optional<String> personOp = subLines.stream().filter(item -> item.startsWith("person:"))
						.map(item -> item.replace("person:", "").trim()).findFirst();
				if (personOp.isPresent()) {
					result.setRegistrantName(personOp.get());
				}

				Optional<String> orgOp = subLines.stream().filter(item -> item.startsWith("organization:"))
						.map(item -> item.replace("organization:", "").trim()).findFirst();
				if (orgOp.isPresent()) {
					result.setRegistrantOrg(orgOp.get());
				}

				Optional<String> cityOp = subLines.stream().filter(item -> item.startsWith("city:"))
						.map(item -> item.replace("city:", "").trim()).findFirst();
				if (cityOp.isPresent()) {
					result.setRegistrantCity(cityOp.get());
				}

				Optional<String> phoneOp = subLines.stream().filter(item -> item.startsWith("phone:"))
						.map(item -> item.replace("phone:", "").trim()).findFirst();
				if (phoneOp.isPresent()) {
					result.setRegistrantPhone(phoneOp.get());
				}

				Optional<String> emailOp = subLines.stream().filter(item -> item.startsWith("e-mail:"))
						.map(item -> item.replace("e-mail:", "").trim()).findFirst();
				if (emailOp.isPresent()) {
					result.setRegistrantEmail(emailOp.get());
				}

				Optional<String> countryOp = subLines.stream().filter(item -> item.startsWith("country:"))
						.map(item -> item.replace("country:", "").trim()).findFirst();
				if (countryOp.isPresent()) {
					result.setRegistrantCountry(countryOp.get());
				}

				Optional<String> stateOp = subLines.stream().filter(item -> item.startsWith("state:"))
						.map(item -> item.replace("state:", "").trim()).findFirst();
				if (stateOp.isPresent()) {
					result.setRegistrantState(stateOp.get());
				}
			}

			// tech
			if (lines.contains("% Technical Contacts:")) {
				int index = lines.indexOf("% Technical Contacts:");
				List<String> subLines = new ArrayList<>();
				for (int i = index; i < lines.size(); i++) {
					String s = lines.get(i);
					if (StringUtils.isBlank(s)) {
						break;
					} else {
						subLines.add(s);
					}
				}

				Optional<String> personOp = subLines.stream().filter(item -> item.startsWith("person:"))
						.map(item -> item.replace("person:", "").trim()).findFirst();
				if (personOp.isPresent()) {
					result.setTechName(personOp.get());
				}

				Optional<String> emailOp = subLines.stream().filter(item -> item.startsWith("e-mail:"))
						.map(item -> item.replace("e-mail:", "").trim()).findFirst();
				if (emailOp.isPresent()) {
					result.setTechEmail(emailOp.get());
				}

				Optional<String> phoneOp = subLines.stream().filter(item -> item.startsWith("phone:"))
						.map(item -> item.replace("phone:", "").trim()).findFirst();
				if (phoneOp.isPresent()) {
					result.setTechPhone(phoneOp.get());
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw e;
		} finally {
			if (br != null) {
				br.close();
			}
		}
	}
}
