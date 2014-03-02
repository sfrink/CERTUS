package server.enumeration;

import java.util.HashMap;
import java.util.Map;

/**
 * @date : Feb 26, 2014
 * @author : Hirosh Wickramasuriya
 */

public enum XmlFileIdentifier {
	
	
	SERVER_CONFIGURATION (5000, "ServerConfigFile", "Server configuration details in xml format.");

 
    private int code;
    private String label;
    private String description;
    
    /**
     * A mapping between the integer code and its corresponding Status to facilitate lookup by code.
     */
    private static Map<Integer, XmlFileIdentifier> codeToStatusMapping;
 
    private XmlFileIdentifier(int code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }
    
    public static XmlFileIdentifier getStatus(int i) {
        if (codeToStatusMapping == null) {
            initMapping();
        }
        return codeToStatusMapping.get(i);
    }
 
    private static void initMapping() {
        codeToStatusMapping = new HashMap<Integer, XmlFileIdentifier>();
        for (XmlFileIdentifier s : values()) {
            codeToStatusMapping.put(s.code, s);
        }
    }
 
    public int getCode() {
        return code;
    }
 
    public String getLabel() {
        return label;
    }
 
    public String getDescription() {
        return description;
    }
 
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Status");
        sb.append("{code=").append(code);
        sb.append(", label='").append(label).append('\'');
        sb.append(", description='").append(description).append('\'');
        sb.append('}');
        return sb.toString();
    }
 

}