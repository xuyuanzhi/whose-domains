package info.wesite.web.controller.tools;

import java.util.ArrayList;
import java.util.List;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import info.wesite.core.entity.Domain;

/** A display-only domain parsed from one RDAP response, never merged with WHOIS/cache fields. */
final class RdapSnapshot {
    private RdapSnapshot() {}

    static Domain parse(String expectedName, String text) {
        JSONObject root = JSONObject.parseObject(text);
        if (root == null || !"domain".equals(root.getString("objectClassName"))
                || !expectedName.equalsIgnoreCase(root.getString("ldhName")) || root.containsKey("errorCode")) {
            throw new IllegalArgumentException("Invalid RDAP domain response");
        }
        Domain result = new Domain();
        result.setName(expectedName);
        result.setRegistryDomainID(root.getString("handle"));
        JSONArray statuses = root.getJSONArray("status");
        if (statuses != null) result.setDomainStatus(String.join(",", statuses.toJavaList(String.class)));
        JSONObject secure = root.getJSONObject("secureDNS");
        if (secure != null && secure.getBoolean("delegationSigned") != null) {
            result.setDnssec(secure.getBoolean("delegationSigned").toString());
        }
        for (Object value : array(root, "events")) {
            if (!(value instanceof JSONObject event)) continue;
            String date = event.getString("eventDate");
            switch (String.valueOf(event.getString("eventAction"))) {
                case "registration" -> result.setRegistCreateDateText(date);
                case "expiration" -> result.setRegistExpiryDateText(date);
                // Database refresh time is not the domain's last-change time.
                case "last changed" -> result.setRegistUpdateDateText(date);
                default -> { }
            }
        }
        List<String> names = new ArrayList<>();
        for (Object value : array(root, "nameservers")) {
            if (value instanceof JSONObject ns && ns.getString("ldhName") != null) names.add(ns.getString("ldhName"));
        }
        result.setNameServers(names.isEmpty() ? null : String.join(",", names));
        for (Object value : array(root, "entities")) {
            if (!(value instanceof JSONObject entity)) continue;
            JSONArray roles = array(entity, "roles");
            boolean registrar = roles.contains("registrar"), registrant = roles.contains("registrant");
            if (!registrar && !registrant) continue;
            if (registrar) {
                for (Object id : array(entity, "publicIds")) {
                    if (id instanceof JSONObject publicId && "IANA Registrar ID".equals(publicId.getString("type"))) {
                        result.setRegistrarIanaID(publicId.getString("identifier"));
                    }
                }
            }
            JSONArray vcard = array(entity, "vcardArray");
            if (vcard.size() < 2 || !(vcard.get(1) instanceof JSONArray fields)) continue;
            for (Object field : fields) {
                if (!(field instanceof JSONArray item) || item.size() < 4) continue;
                String name = item.getString(0);
                if (registrar) {
                    if ("fn".equals(name)) result.setRegistrar(item.getString(3));
                    if ("contact-uri".equals(name)) result.setRegistrarUrl(item.getString(3));
                }
                if (registrant) {
                    switch (String.valueOf(name)) {
                        case "fn" -> result.setRegistrantName(item.getString(3));
                        case "org" -> result.setRegistrantOrg(item.getString(3));
                        case "email" -> result.setRegistrantEmail(item.getString(3));
                        case "tel" -> result.setRegistrantPhone(item.getString(3));
                        case "adr" -> {
                            if (item.get(1) instanceof JSONObject params) result.setRegistrantCountry(params.getString("cc"));
                        }
                        default -> { }
                    }
                }
            }
        }
        return result;
    }

    private static JSONArray array(JSONObject object, String key) {
        return object.get(key) instanceof JSONArray values ? values : new JSONArray();
    }
}
