package server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;



public class ConfigurationProperties {

	private static Properties properties;
	
	static
	{
		loadProperties();
	}
	
	public static void loadProperties() {
		Properties prop = new Properties();
		InputStream input = ConfigurationProperties.class.getClassLoader().getResourceAsStream("config.properties");;
		
		try {
			// load a properties file
			prop.load(input);
	 
			// get the property value and print it out
			//System.out.println(prop.toString());
			
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		
		properties = prop;
	}
	
	public static String dbHost()
	{
		return properties.getProperty("db_host");
	}

	public static String dbPort()
	{
		return properties.getProperty("db_port");
	}
	
	public static String dbSchema()
	{
		return properties.getProperty("db_schema");
	}
	
	public static String dbUser()
	{
		return properties.getProperty("db_user");
	}
	
	public static String dbPassword()
	{
		return properties.getProperty("db_password");
	}
	
	public static String rmiBasePath()
	{
		return properties.getProperty("rmi_basepath");
	}
	
	public static String rmiPort()
	{
		return properties.getProperty("rmi_port");
	}
	
	public static String rmiRegistry()
	{
		return properties.getProperty("rmi_registry");
	}
	
	public static String rmiFilePolicy()
	{
		return properties.getProperty("rmi_file_policy");
	}
	
	public static String rmiFileKeystore()
	{
		return properties.getProperty("rmi_file_keystore");
	}
	
	public static String rmiPasswordKeystore()
	{
		return properties.getProperty("rmi_file_keystore_password");
	}
	
	public static String emailAddress(){
		return properties.getProperty("email_address");
	}

	public static String emailPassword(){
		return properties.getProperty("email_password");
	}
	
	public static String smtpServer(){
		return properties.getProperty("email_smtp_server");
	}
	
	public static String smtpPort(){
		return properties.getProperty("email_smtp_port");
	}

	
	public static String emailSystemUrl(){
		return properties.getProperty("email_system_url");
	}
	
	public static String resetPasswordUrl(){
		return properties.getProperty("email_password_reset_url");
	}
	
	public static String logFilename(){
		return properties.getProperty("log_filename");
	}
}
