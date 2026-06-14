package info.wesite.core.handler;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;

import jakarta.annotation.PostConstruct;

@Component
public class Geoip2Handler {

    private static final Logger log = LoggerFactory.getLogger(Geoip2Handler.class);

    private static DatabaseReader cityReader;
    
    private static DatabaseReader asnReader;
    
    @Value("${maxmind.city.file.path}")
    private String cityFilePath;
    @Value("${maxmind.asn.file.path}")
    private String asnFilePath;

    @PostConstruct
    public void init() {
        try {
        	cityReader = new DatabaseReader.Builder(new File(cityFilePath)).build();
        	asnReader = new DatabaseReader.Builder(new File(asnFilePath)).build();
        } catch (IOException e) {
            log.error("Failed to initialize GeoIP2 database readers", e);
        }
    }

    public String getCityJson(String ip) {
		try {
			InetAddress inetAddress = InetAddress.getByName(ip);
			CityResponse resp = cityReader.city(inetAddress);
	    	if (resp == null) {
	    		return null;
	    	} else {
	    		return resp.toJson();
	    	}
		} catch (IOException | GeoIp2Exception e) {
			log.warn("Failed to get city info for IP: {}", ip, e);
			return null;
		}
    }
    
    public String getAsnJson(String ip) {
		try {
			InetAddress inetAddress = InetAddress.getByName(ip);
			AsnResponse resp = asnReader.asn(inetAddress);
	    	if (resp == null) {
	    		return null;
	    	} else {
	    		return resp.toJson();
	    	}
		} catch (IOException | GeoIp2Exception e) {
			log.warn("Failed to get ASN info for IP: {}", ip, e);
			return null;
		}
    }

    /**
     * 获取IP的地理位置描述，如 "San Francisco, California, US"
     */
    public String getServerLocation(String ip) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ip);
            CityResponse resp = cityReader.city(inetAddress);
            if (resp == null) return null;
            StringBuilder sb = new StringBuilder();
            if (resp.getCity() != null && resp.getCity().getName() != null) {
                sb.append(resp.getCity().getName());
            }
            if (resp.getMostSpecificSubdivision() != null && resp.getMostSpecificSubdivision().getName() != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(resp.getMostSpecificSubdivision().getName());
            }
            if (resp.getCountry() != null && resp.getCountry().getIsoCode() != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(resp.getCountry().getIsoCode());
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (IOException | GeoIp2Exception e) {
            log.warn("Failed to get server location for IP: {}", ip, e);
            return null;
        }
    }

    /**
     * 获取IP的ASN组织名称（托管商）
     */
    public String getAsnOrgName(String ip) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ip);
            AsnResponse resp = asnReader.asn(inetAddress);
            if (resp == null) return null;
            return resp.getAutonomousSystemOrganization();
        } catch (IOException | GeoIp2Exception e) {
            log.warn("Failed to get ASN org for IP: {}", ip, e);
            return null;
        }
    }
}