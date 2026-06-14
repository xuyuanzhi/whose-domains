package info.wesite.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.xbill.DNS.Type;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName("WEB_DOMAIN_DNS")
@Data
public class DomainDns extends BaseEntity {

	public final static String TYPE_A = Type.string(Type.A);
	public final static String TYPE_AAAA = Type.string(Type.AAAA);
	public final static String TYPE_CNAME = Type.string(Type.CNAME);
	public final static String TYPE_MX = Type.string(Type.MX);
	public final static String TYPE_TXT = Type.string(Type.TXT);

	// 域名表主键
	private String domainId;

	// 记录所属域名
	private String name;

	// 记录值
	private String value;

	// 类型，例如A,TXT
	private String type;

	// 缓存时长
	private long ttl;

	// 城市信息
	private String cityJson;

	// asn信息
	private String asnJson;

	public String getDisplayValue() {
		if (TYPE_A.equals(type) || TYPE_AAAA.equals(type)) {
			String addr = getAddress();
			String asoName = getAsoName();
			if (addr == null && asoName == null) {
				return value;
			} else if (addr != null && asoName != null) {
				return value + "<br/>" + asoName + "<br/>" + addr;
			} else if (addr != null) {
				return value + "<br/>" + addr;
			} else {
				return value + "<br/>" + asoName;
			}
		} else if (TYPE_CNAME.equals(type)) {
			String removePoint = value.substring(0, value.length() - 1);
			return "<a href=\"/domain/" + removePoint + "\">" + value + "</a>";
		} else {
			return value;
		}

	}

	public String getAsoName() {
		if (StringUtils.isBlank(asnJson)) {
			return null;
		}

		JSONObject json = JSON.parseObject(asnJson);
		if (json == null) {
			return null;
		} else {
			return json.getString("autonomous_system_organization");
		}
	}

	public String getAddress() {
		if (StringUtils.isBlank(cityJson)) {
			return null;
		}

		JSONObject json = JSON.parseObject(cityJson);
		if (json == null) {
			return null;
		} else {
			List<String> list = new ArrayList<>();
			if (json.containsKey("city") && json.getJSONObject("city").containsKey("names")) {
				list.add(json.getJSONObject("city").getJSONObject("names").getString("en"));
			}
			if (json.containsKey("subdivisions")) {
				JSONArray arr = json.getJSONArray("subdivisions");
				if (arr.size() > 0 && arr.getJSONObject(0).containsKey("names")) {
					list.add(arr.getJSONObject(0).getJSONObject("names").getString("en"));
				}
			}
			if (json.containsKey("country") && json.getJSONObject("country").containsKey("names")) {
				list.add(json.getJSONObject("country").getJSONObject("names").getString("en"));
			}
			if (json.containsKey("continent") && json.getJSONObject("continent").containsKey("names")) {
				list.add(json.getJSONObject("continent").getJSONObject("names").getString("en"));
			}
			return StringUtils.join(list, ", ");
		}
	}
}
