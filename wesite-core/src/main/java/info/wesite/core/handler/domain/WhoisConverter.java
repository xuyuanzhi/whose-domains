package info.wesite.core.handler.domain;

import java.io.IOException;

import info.wesite.core.entity.Domain;

public interface WhoisConverter {
	
	public static final String Registry_Domain_ID = "Registry Domain ID:";
	public static final String ROID = "ROID:";
	public static final String Registrar_WHOIS_Server = "Registrar WHOIS Server:";
	public static final String Registrar_URL = "Registrar URL:";
	public static final String Registrar = "Registrar:";
	public static final String Registrar_Name = "Registrar Name:";
	public static final String Sponsoring_Registrar = "Sponsoring Registrar:";
	public static final String Registrar_IANA_ID = "Registrar IANA ID:";
	public static final String Registrar_Abuse_Contact_Email = "Registrar Abuse Contact Email:";
	public static final String Registrar_Abuse_Contact_Phone = "Registrar Abuse Contact Phone:";
	public static final String Updated_Date = "Updated Date:";
	public static final String Creation_Date = "Creation Date:";
	public static final String Registrar_Registration_Expiration_Date = "Registrar Registration Expiration Date:";
	public static final String Registry_Expiry_Date = "Registry Expiry Date:";
	public static final String Domain_Status = "Domain Status:";
	public static final String Status = "Status:";
	public static final String Last_Modified = "Last Modified:";
	
	public static final String Registrant = "Registrant:";
	public static final String Registrant_Organization = "Registrant Organization:";
	public static final String Registrant_Name = "Registrant Name:";
	public static final String Registrant_Street = "Registrant Street:";
	public static final String Registrant_City = "Registrant City:";
	public static final String Registrant_State_Province = "Registrant State/Province:";
	public static final String Registrant_Postal_Code = "Registrant Postal Code:";
	public static final String Registrant_Country = "Registrant Country:";
	public static final String Registrant_Phone = "Registrant Phone:";
	public static final String Registrant_Phone_Ext = "Registrant Phone Ext:";
	public static final String Registrant_Fax = "Registrant Fax:";
	public static final String Registrant_Fax_Ext = "Registrant Fax Ext:";
	public static final String Registrant_Email = "Registrant Email:";
	public static final String Registrant_Contact_Email = "Registrant Contact Email:";
	
	public static final String Tech_Name = "Tech Name:";
	public static final String Tech_Email = "Tech Email:";
	public static final String Tech_Phone = "Tech Phone:";
	public static final String Name_Server = "Name Server:";
	public static final String DNSSEC = "DNSSEC:";
	
	public static final String Registration_Time = "Registration Time:";
	public static final String Expiration_Time = "Expiration Time:";
	
	//.edu
	public static final String Administrative_Contact = "Administrative Contact:";
	public static final String Technical_Contact = "Technical Contact:";
	public static final String Name_Servers = "Name Servers:";
	public static final String Domain_record_activated = "Domain record activated:";
	public static final String Domain_record_last_updated = "Domain record last updated:";
	public static final String Domain_expires = "Domain expires:";
	

	public void fillDomainWithText(Domain result, String text) throws IOException;
}
