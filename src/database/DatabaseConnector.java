package database;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.rowset.serial.SerialBlob;

import com.mysql.jdbc.exceptions.jdbc4.CommunicationsException;
import com.mysql.jdbc.exceptions.jdbc4.MySQLNonTransientConnectionException;

import server.ConfigurationProperties;
import server.DataEncryptor;
import server.EmailExchanger;
import server.PasswordHasher;
import server.RSAKeys;
import server.SecurityValidator;
import dto.ActionDto;
import dto.CandidateDto;
import dto.ElectionDto;
import dto.ElectionProgressDto;
import dto.InputValidation;
import dto.UserDto;
import dto.Validator;
import dto.VoteDto;
import enumeration.ElectionType;
import enumeration.Status;
import enumeration.ElectionStatus;
import enumeration.UserType;
import enumeration.UserStatus;


public class DatabaseConnector
{
	private static String	dbHost;
	private static String	dbPort;
	private static String	dbUser;
	private static String	dbPassword;
	private static String	dbName;
	private Connection		con;
	private static String 	newLine = System.getProperty("line.separator");
	
	public DatabaseConnector()
	{
		Connection con = null;

		dbHost = ConfigurationProperties.dbHost();
		dbPort = ConfigurationProperties.dbPort();
		dbName = ConfigurationProperties.dbSchema();
		dbUser = ConfigurationProperties.dbUser();
		dbPassword = ConfigurationProperties.dbPassword();

		try {
			Class.forName("com.mysql.jdbc.Driver");
			String url = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName;
			con = DriverManager.getConnection(url, dbUser, dbPassword);
			this.con = con;
		} catch (Exception e) {
			System.out.println("Db connection failed");
			e.printStackTrace();
		}

	}

	
	public void reconnectToDb() {
		try {
			Class.forName("com.mysql.jdbc.Driver");
			String url = "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName;
			con = DriverManager.getConnection(url, dbUser, dbPassword);
			this.con = con;
		} catch (Exception e) {
			System.out.println("Db connection failed");
			e.printStackTrace();
		}
	}
	
	public UserDto selectUserById(int userId) {
		UserDto u = new UserDto();

		PreparedStatement st = null;
		String query = "SELECT * FROM users WHERE user_id = ?";

		try {
			st = con.prepareStatement(query);
			st.setInt(1, userId);
			ResultSet res = st.executeQuery();

			if (res.next()) {
				u.setUserId(res.getInt(1));
				u.setFirstName(res.getString(2));
				u.setLastName(res.getString(3));
				u.setEmail(res.getString(4));
				u.setPassword(res.getString(5));
				u.setSalt(res.getString(6));
				u.setTempPassword(res.getString(7));
				u.setTempSalt(res.getString(8));
				u.setActivationCode(res.getString(9));
				u.setPublicKey(res.getString(10));
				u.setType(res.getInt(11));
				u.setStatus(res.getInt(12));
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}

		return u;
	}

	/**
	 * Checks whether the given password is correct for the specified user account (email address)
	 * @param email
	 * @param plainPass
	 * @return Validator
	 */
	public Validator checkIfUsernamePasswordMatch(String email, String plainPass) {
		// 1. validate input
		Validator result = validateEmailAndPlainInput(email, plainPass);
		if (!result.isVerified()) {
			return result;
		}

		// 2. validate email
		result = checkUserEmail(email);
		if (!result.isVerified()) {
			return result;
		}

		// get this user limited info from the database
		UserDto userDto = selectUserByEmailLimited(email);

		String dbHash = userDto.getPassword();
		String dbSalt = userDto.getSalt();

		String plainHash = PasswordHasher.sha512(plainPass, dbSalt);

		// 3. if entered password is correct, return true with welcome message
		if (plainHash.equals(dbHash)) {
			
			result.setObject(userDto);
			result.setVerified(true);
			result.setStatus("Welcome to Certus");

			return result;
		} else {
			result.setVerified(false);
			result.setStatus("Error, the system could not resolve the provided combination of username and password.");
			return result;
		}

	}

	/**
	 * Validates password against email address. 
	 * @param email
	 * @param plainPass
	 * @return Validator
	 */
	public Validator validateEmailAndPlainInput(String email, String plainPass) {
		InputValidation iv = new InputValidation();
		Validator vResult = new Validator();
		Validator vEmail, vPlain;
		Boolean verified = true;
		String status = "";

		// 1. email
		vEmail = iv.validateEmail(email, "Email");
		verified &= vEmail.isVerified();
		status += vEmail.getStatus();

		// 2. plain
		vPlain = iv.validateString(plainPass, "Password");
		verified &= vPlain.isVerified();
		status += vPlain.getStatus();

		vResult.setVerified(verified);
		vResult.setStatus(status);

		return vResult;
	}

	private Validator checkUserEmail(String emailToSelect) {
		Validator v = new Validator();
		v.setVerified(false);
		v.setStatus("Error, the system could not resolve the provided email.");

		PreparedStatement st = null;
		String query = "SELECT user_id FROM users WHERE email = ? AND status = 1";

		try {
			st = con.prepareStatement(query);
			st.setString(1, emailToSelect);

			ResultSet res = st.executeQuery();

			if (res.next()) {
				v.setVerified(true);
				v.setStatus("");
				return v;
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}

		return v;
	}

	/**
	 * Selects only a limited user information of a given email address
	 * @param emailToSelect
	 * @return UserDto
	 */
	public UserDto selectUserByEmailLimited(String emailToSelect) {
		UserDto userDto = new UserDto();

		PreparedStatement st = null;

		String query = "SELECT user_id, first_name, last_name, password, salt, status, type, "
				+ "temp_password, temp_salt"
				+ " FROM users WHERE email = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setString(1, emailToSelect);
			
			ResultSet res = st.executeQuery();

			if (res.next()) {
				int user_id = res.getInt(1);
				String first_name = res.getString(2);
				String last_name = res.getString(3);
				String password = res.getString(4);
				String salt = res.getString(5);
				int statusId = res.getInt(6);
				int type = res.getInt(7);
				
				userDto.setEmail(emailToSelect);
				userDto.setUserId(user_id);
				userDto.setFirstName(first_name);
				userDto.setLastName(last_name);
				userDto.setPassword(password);
				userDto.setSalt(salt);
				userDto.setStatus(statusId);
				userDto.setType(type);
				userDto.setTempPassword(res.getString(8));
				userDto.setTempSalt(res.getString(9));
				
			} else {
				userDto=null;
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}

		return userDto;
	}

	/**
	 * This function selects an election for owner
	 * @param id (int) - Election identification number (primary key)
	 * @return Validator : ElectionDto - Details of a particular election
	 * @author Hirosh Wickramasuriya, Dmitriy Karmazin
	 */
	public Validator selectElectionForOwner(int id) {
		Validator validator = new Validator();
		ElectionDto electionDto = new ElectionDto();

		PreparedStatement st = null;

		String query = "SELECT election_id, election_name, e.description, start_datetime, close_datetime, "
				+ " status, s.code, s.description, owner_id, candidates_string, type, allowed_users_emails"
				+ " FROM election e "
				+ " INNER JOIN status_election s "
				+ " ON (e.status = s.status_id) "
				+ " WHERE election_id = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, id);
			ResultSet res = st.executeQuery();
			if (res.next()) {
				int electionId = res.getInt(1);
				String electionName = res.getString(2);
				String electionDescription = res.getString(3);
				String startDatetime = res.getString(4);
				String closeDatetime = res.getString(5);
				int statusId = res.getInt(6);
				String statusCode = res.getString(7);
				String statusDescription = res.getString(8);
				int ownerId = res.getInt(9);
				String candidatesListString = res.getString(10);
				int electionType = res.getInt(11);
				String allowedUserEmails = res.getString(12);
				
				electionDto.setElectionId(electionId);
				electionDto.setElectionName(electionName);
				electionDto.setElectionDescription(electionDescription);
				electionDto.setStartDatetime(startDatetime);
				electionDto.setCloseDatetime(closeDatetime);
				electionDto.setStatus(statusId);
				electionDto.setStatusCode(statusCode);
				electionDto.setStatusDescription(statusDescription);
				electionDto.setOwnerId(ownerId);
				electionDto.setCandidatesListString(candidatesListString);
				electionDto.setElectionType(electionType);
				electionDto.setRegisteredEmailList(allowedUserEmails);
				
				Validator vCandidates = selectCandidatesOfElection(electionId);
				electionDto.setCandidateList( (ArrayList<CandidateDto>) vCandidates.getObject());
				
				validator.setVerified(true);
				validator.setObject(electionDto);
				validator.setStatus("Select successful");
			} else {
				validator.setStatus("Election not found");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			validator.setVerified(false);
			validator.setStatus("Select failed");
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			validator.setVerified(false);
			validator.setStatus("Select failed");
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}

		return validator;
	}

	
	// Election
	/**
	 * This function selects an election for Voter
	 * @param id (int) - Election identification number (primary key)
	 * @return Validator : ElectionDto - Details of a particular election
	 * @author Hirosh Wickramasuriya, Dmitriy Karmazin
	 */
	public Validator selectElectionForVoter(int id) {
		Validator validator = new Validator();
		ElectionDto electionDto = new ElectionDto();

		PreparedStatement st = null;

		String query = "SELECT election_id, election_name, e.description, start_datetime, close_datetime, "
				+ " status, s.code, s.description, owner_id, candidates_string, type, allowed_users_emails"
				+ " FROM election e "
				+ " INNER JOIN status_election s "
				+ " ON (e.status = s.status_id) "
				+ " WHERE election_id = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, id);
			ResultSet res = st.executeQuery();
			if (res.next()) {
				int electionId = res.getInt(1);
				String electionName = res.getString(2);
				String electionDescription = res.getString(3);
				String startDatetime = res.getString(4);
				String closeDatetime = res.getString(5);
				int statusId = res.getInt(6);
				String statusCode = res.getString(7);
				String statusDescription = res.getString(8);
				int ownerId = res.getInt(9);
				String candidatesListString = res.getString(10);
				int electionType = res.getInt(11);
				String allowedUserEmails = res.getString(12);
				
				
				electionDto.setElectionId(electionId);
				electionDto.setElectionName(electionName);
				electionDto.setElectionDescription(electionDescription);
				electionDto.setStartDatetime(startDatetime);
				electionDto.setCloseDatetime(closeDatetime);
				electionDto.setStatus(statusId);
				electionDto.setStatusCode(statusCode);
				electionDto.setStatusDescription(statusDescription);
				electionDto.setOwnerId(ownerId);
				electionDto.setCandidatesListString(candidatesListString);
				electionDto.setElectionType(electionType);
				electionDto.setRegisteredEmailList(allowedUserEmails);
				
				Validator vCandidates = selectCandidatesOfElection(electionId);
				electionDto.setCandidateList( (ArrayList<CandidateDto>) vCandidates.getObject());
				
				validator.setVerified(true);
				validator.setObject(electionDto);
				validator.setStatus("Select successful");
			} else {
				validator.setStatus("Election not found");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setVerified(false);
			validator.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setVerified(false);
			validator.setStatus("Select failed");
		}
		
		return validator;
	}

	
	/**
	 * Selects elections full details including list of candidates (new line separated string) 
	 * and allowed user emails (new line separated string)
	 * @param id
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectElectionFullDetail(int id) {
		Validator validator = new Validator();
		ElectionDto electionDto = new ElectionDto();

		PreparedStatement st = null;

		String query = "SELECT e.election_id, election_name, e.description, start_datetime, close_datetime, "
				+ " e.status, s.code, s.description, owner_id, candidates_string, type, allowed_users_emails"
				+ " FROM election e "
				+ " INNER JOIN status_election s "
				+ " ON (e.status = s.status_id) "
				+ " WHERE e.election_id = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, id);
			ResultSet res = st.executeQuery();
			if (res.next()) {
				int electionId = res.getInt(1);
				String electionName = res.getString(2);
				String electionDescription = res.getString(3);
				String startDatetime = res.getString(4);
				String closeDatetime = res.getString(5);
				int statusId = res.getInt(6);
				String statusCode = res.getString(7);
				String statusDescription = res.getString(8);
				int ownerId = res.getInt(9);
				String candidatesListString = res.getString(10);
				int electionType = res.getInt(11);
				String allowedUserEmails = (res.getString(12) == null) ? "" : res.getString(12);
				
				electionDto.setElectionId(electionId);
				electionDto.setElectionName(electionName);
				electionDto.setElectionDescription(electionDescription);
				electionDto.setStartDatetime(startDatetime);
				electionDto.setCloseDatetime(closeDatetime);
				electionDto.setStatus(statusId);
				electionDto.setStatusCode(statusCode);
				electionDto.setStatusDescription(statusDescription);
				electionDto.setOwnerId(ownerId);
				electionDto.setCandidatesListString(candidatesListString);
				electionDto.setElectionType(electionType);
				electionDto.setRegisteredEmailList(allowedUserEmails);
				
				electionDto.setCurrentEmailList(selectParticipatingVotersOfElection(electionId));
				
				electionDto.setCandidateList((ArrayList<CandidateDto>) selectCandidatesOfElection(
						electionId
						, Status.ENABLED).getObject());
				
				validator.setVerified(true);
				validator.setObject(electionDto);
				validator.setStatus("Select successful");
			} else {
				validator.setStatus("Election not found");
			}
			

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setVerified(false);
			validator.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setVerified(false);
			validator.setStatus("Select failed");
		}

		return validator;
	}

	/**
	 * Select list of elections which are available to Administrator
	 * @param status
	 *            (ElectionStatus) - specific status to be searched
	 * @return Validator : ArrayList<ElectionDto> - List of elections that
	 *         does not match to the status
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectElectionsForAdmin() {
		Validator validator = new Validator();
		ArrayList<ElectionDto> elections = new ArrayList<ElectionDto>();
		ElectionStatus electionStatus = ElectionStatus.DELETED;
		
		PreparedStatement st = null;

		String query = "SELECT election_id, election_name, e.description, start_datetime, close_datetime,"
				+ " status, s.code, s.description, owner_id, candidates_string "
				+ " FROM election e"
				+ " INNER JOIN status_election s "
				+ " ON (e.status = s.status_id) "
				+ " WHERE status <> ?"
				+ " ORDER BY election_id";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, electionStatus.getCode());

			ResultSet res = st.executeQuery();

			while (res.next()) {

				int electionId = res.getInt(1);
				String electionName = res.getString(2);
				String electionDescription = res.getString(3);
				String startDatetime = res.getString(4);
				String closeDatetime = res.getString(5);
				int statusId = res.getInt(6);
				String statusCode = res.getString(7);
				String statusDescription = res.getString(8);
				int ownerId = res.getInt(9);
				String candidatesListString = res.getString(10);
				
				ElectionDto electionDto = new ElectionDto();
				electionDto.setElectionId(electionId);
				electionDto.setElectionName(electionName);
				electionDto.setElectionDescription(electionDescription);
				electionDto.setStartDatetime(startDatetime);
				electionDto.setCloseDatetime(closeDatetime);
				electionDto.setStatus(statusId);
				electionDto.setStatusCode(statusCode);
				electionDto.setStatusDescription(statusDescription);
				electionDto.setOwnerId(ownerId);
				electionDto.setCandidatesListString(candidatesListString);

				elections.add(electionDto);
			}
			validator.setVerified(true);
			validator.setObject(elections);
			validator.setStatus("Successfully selected");

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select Failed.");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select Failed.");
		}

		return validator;

	}

	
	/**
	 * This function selects all elections owned by a given user with published results
	 * @param userId (int) - user_id of the user who owns this election
	 * @param status (ElectionStatus) - specific status to be searched
	 * @return Validator : ArrayList<ElectionDto> - List of elections owned by
	 *         the specific user, that matches a specific status
	 * @author Hirosh Wickramasuriya, Dmitriy Karmazin
	 */
	public Validator selectElectionsForResults(int userId) {
		Validator validator = new Validator();
		ArrayList<ElectionDto> elections = new ArrayList<ElectionDto>();
		PreparedStatement st = null;
		int electionStatus = ElectionStatus.PUBLISHED.getCode();
		
		String query = "";
		query += "SELECT election_id, election_name, e.description, start_datetime, close_datetime, status, s.code, s.description, owner_id, candidates_string, type";
		query += " FROM election e";
		query += " INNER JOIN status_election s ON (e.status = s.status_id) WHERE owner_id = ? AND status = ?";
		query += " UNION";
		query += " SELECT election_id, election_name, e.description, start_datetime, close_datetime, status, s.code, s.description, owner_id, candidates_string, type";
		query += " FROM election e";
		query += " INNER JOIN status_election s ON (e.status = s.status_id) WHERE e.type=? AND status = ?";
		query += " UNION";
		query += " SELECT e.election_id, election_name, e.description, start_datetime, close_datetime, status, s.code, s.description, owner_id, candidates_string, type";
		query += " FROM election e";
		query += " INNER JOIN status_election s ON (e.status = s.status_id)";
		query += " INNER JOIN participate p";
		query += " ON (e.election_id = p.election_id)";
		query += " WHERE (e.status = ? AND e.type = ? AND p.user_id = ? AND e.election_id IN (SELECT e.election_id FROM vote WHERE user_id = ?) )";
		query += " GROUP BY election_id";
		query += " ORDER BY election_id DESC";

		try {
			st = this.con.prepareStatement(query);

			st.setInt(1, userId);
			st.setInt(2, electionStatus);
			st.setInt(3, ElectionType.PUBLIC.getCode());
			st.setInt(4, electionStatus);
			st.setInt(5, electionStatus);
			st.setInt(6, ElectionType.PRIVATE.getCode());
			st.setInt(7, userId);
			st.setInt(8, userId);

			ResultSet res = st.executeQuery();

			while (res.next()) {

				int electionId = res.getInt(1);
				String electionName = res.getString(2);
				String electionDescription = res.getString(3);
				String startDatetime = res.getString(4);
				String closeDatetime = res.getString(5);
				int statusId = res.getInt(6);
				String statusCode = res.getString(7);
				String statusDescription = res.getString(8);
				int ownerId = res.getInt(9);
				String candidatesListString = res.getString(10);
				int electionType = res.getInt(11);
				
				ElectionDto electionDto = new ElectionDto();
				electionDto.setElectionId(electionId);
				electionDto.setElectionName(electionName);
				electionDto.setElectionDescription(electionDescription);
				electionDto.setStartDatetime(startDatetime);
				electionDto.setCloseDatetime(closeDatetime);
				electionDto.setStatus(statusId);
				electionDto.setStatusCode(statusCode);
				electionDto.setStatusDescription(statusDescription);
				electionDto.setOwnerId(ownerId);
				electionDto.setCandidatesListString(candidatesListString);
				electionDto.setElectionType(electionType);
				elections.add(electionDto);
			}
			validator.setVerified(true);
			validator.setObject(elections);
			validator.setStatus("Successfully selected");

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		}

		return validator;
	}

	

	/**
	 * Select list of elections which are owned by a specific owner id
	 * @param election_owner_id (int) - user_id of the user who owns elections
	 * @return Validator : ArrayList<ElectionDto> - List of all the elections (not disabled only)
	 *         owned by the specific user (regardless of status)
	 * @author Hirosh Wickramasuriya, Dmitriy Karmazin
	 */
	public Validator selectElectionsForOwner(int electionOwnerId) {
		Validator validator = new Validator();
		ArrayList<ElectionDto> elections = new ArrayList<ElectionDto>();

		PreparedStatement st = null;

		String query = "SELECT election_id, election_name, e.description, start_datetime, close_datetime,"
				+ " status, s.code, s.description, owner_id, candidates_string, type "
				+ " FROM election e"
				+ " INNER JOIN status_election s "
				+ " ON (e.status = s.status_id) "
				+ " WHERE owner_id = ? "
				+ " AND status <> " + ElectionStatus.DELETED.getCode()
				+ " ORDER BY election_id DESC";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, electionOwnerId);

			ResultSet res = st.executeQuery();

			while (res.next()) {

				int electionId = res.getInt(1);
				String electionName = res.getString(2);
				String electionDescription = res.getString(3);
				String startDatetime = res.getString(4);
				String closeDatetime = res.getString(5);
				int statusId = res.getInt(6);
				String statusCode = res.getString(7);
				String statusDescription = res.getString(8);
				int ownerId = res.getInt(9);
				String candidatesListString = res.getString(10);
				int electionType = res.getInt(11);

				ElectionDto electionDto = new ElectionDto();
				electionDto.setElectionId(electionId);
				electionDto.setElectionName(electionName);
				electionDto.setElectionDescription(electionDescription);
				electionDto.setStartDatetime(startDatetime);
				electionDto.setCloseDatetime(closeDatetime);
				electionDto.setStatus(statusId);
				electionDto.setStatusCode(statusCode);
				electionDto.setStatusDescription(statusDescription);
				electionDto.setOwnerId(ownerId);
				electionDto.setCandidatesListString(candidatesListString);
				electionDto.setElectionType(electionType);

				elections.add(electionDto);
			}
			validator.setVerified(true);
			validator.setObject(elections);
			validator.setStatus("Successfully selected");

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		}

		return validator;

	}

	/**
	 * This function selects all elections which are available to vote for a given user
	 * @param userId (int) - user_id of the user who owns this election
	 * @param status (ElectionStatus) - specific status to be searched
	 * @return Validator : ArrayList<ElectionDto> - List of elections owned by
	 *         the specific user, that matches a specific status
	 * @author Hirosh Wickramasuriya, Dmitriy Karmazin
	 */
	public Validator selectElectionsForVoter(int user_id) {
		Validator val = new Validator();
		ArrayList<ElectionDto> elecs = new ArrayList<ElectionDto>();
		PreparedStatement st = null;

		String query = "SELECT e.election_id, e.election_name, e.description, e.owner_id, "
				+ " e.start_datetime, e.close_datetime"
				+ " FROM election e "
				+ " WHERE (e.status = ? AND e.type = ? AND e.election_id NOT IN (SELECT election_id FROM vote WHERE user_id = ?)) "
				+ " UNION"
				+ "  SELECT e.election_id, e.election_name, e.description, e.owner_id, "
				+ " e.start_datetime, e.close_datetime "
				+ " FROM election e "
				+ " INNER JOIN participate p "
				+ " ON (e.election_id = p.election_id) "
				+ " WHERE (e.status = ? AND e.type = ? AND p.user_id = ? AND e.election_id NOT IN (SELECT election_id FROM vote WHERE user_id = ?) )"
				;

		
		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, ElectionStatus.OPEN.getCode());
			st.setInt(2, ElectionType.PUBLIC.getCode());
			st.setInt(3, user_id);
			
			st.setInt(4, ElectionStatus.OPEN.getCode());
			st.setInt(5, ElectionType.PRIVATE.getCode());
			st.setInt(6, user_id);
			st.setInt(7, user_id);
			ResultSet res = st.executeQuery();

			while (res.next()) {
				ElectionDto e = new ElectionDto();
				e.setElectionId(res.getInt(1));
				e.setCandidateList((ArrayList<CandidateDto>) selectCandidatesOfElection(
						e.getElectionId()
						, Status.ENABLED).getObject());
				e.setElectionName(res.getString(2));
				e.setElectionDescription(res.getString(3));
				e.setOwnerId(res.getInt(4));
				e.setStartDatetime(res.getString(5));
				e.setCloseDatetime(res.getString(6));
				
				elecs.add(e);
			}
			val.setStatus("Retrieved Elections");
			val.setVerified(true);
			val.setObject(elecs);
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("Select failed");
			val.setVerified(false);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("Select failed");
			val.setVerified(false);
		}
		return val;

	}

	// Candidates
	/**
	 * Selects a specific candidate
	 * @param id
	 *            - candidate identification number (primary key)
	 * @return Validator :CandidateDto - Details of a particular candidate
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectCandidate(int id) {
		Validator validator = new Validator();
		CandidateDto candidateDto = new CandidateDto();

		PreparedStatement st = null;

		String query = "SELECT candidate_id, candidate_name, election_id, display_order, status FROM candidate WHERE candidate_id = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, id);

			ResultSet res = st.executeQuery();

			if (res.next()) {

				int candidateId = res.getInt(1);
				String candidate_name = res.getString(2);
				int electionId = res.getInt(3);
				int displayOrder = res.getInt(4);
				int statusId = res.getInt(5);

				candidateDto.setCandidateId(candidateId);
				candidateDto.setCandidateName(candidate_name);
				candidateDto.setElectionId(electionId);
				candidateDto.setDisplayOrder(displayOrder);
				candidateDto.setStatus(statusId);

				validator.setVerified(true);
				validator.setObject(candidateDto);
				validator.setStatus("Successfully selected");
			} else {
				validator.setStatus("Candidate not found");
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		}

		return validator;
	}

	/**
	 * Selects list of candidates of a given election
	 * @param electionIdKey
	 *            - election identification number
	 * @return Validator : ArrayList<CandidateDto>- list of all the candidates
	 *         under specified election
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectCandidatesOfElection(int electionIdKey) {
		Validator validator = new Validator();
		ArrayList<CandidateDto> candidates = new ArrayList<CandidateDto>();

		PreparedStatement st = null;

		String query = "SELECT candidate_id, candidate_name, election_id, display_order, status FROM candidate WHERE election_id = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, electionIdKey);

			ResultSet res = st.executeQuery();

			while (res.next()) {

				int candidateId = res.getInt(1);
				String candidateName = res.getString(2);
				int electionId = res.getInt(3);
				int displayOrder = res.getInt(4);
				int statusId = res.getInt(5);

				CandidateDto candidateDto = new CandidateDto();
				candidateDto.setCandidateId(candidateId);
				candidateDto.setCandidateName(candidateName);
				candidateDto.setElectionId(electionId);
				candidateDto.setDisplayOrder(displayOrder);
				candidateDto.setStatus(statusId);

				candidates.add(candidateDto);
			}
			validator.setVerified(true);
			validator.setObject(candidates);
			validator.setStatus("Successfully selected");

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setStatus("select failed");
		}

		return validator;
	}

	/**
	 * Selects list of candidates of an election whose status matches to the given status
	 * @param electionIdKey
	 *            - election identification number
	 * @param candidateStatus
	 *            - desired status of candidate which required to be returned
	 *            for given election
	 * @return Validator :ArrayList<CandidateDto> - list of all the candidates
	 *         that matches the status under specified election
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectCandidatesOfElection(int electionIdKey, Status candidateStatus) {
		Validator validator = new Validator();
		ArrayList<CandidateDto> candidates = new ArrayList<CandidateDto>();

		PreparedStatement st = null;

		String query = "SELECT candidate_id, candidate_name, election_id, display_order, status " + "	FROM candidate "
				+ " WHERE election_id = ?" + " AND status = ?";

		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, electionIdKey);
			st.setInt(2, candidateStatus.getCode());
			ResultSet res = st.executeQuery();

			while (res.next()) {

				int candidateId = res.getInt(1);
				String candidateName = res.getString(2);
				int electionId = res.getInt(3);
				int displayOrder = res.getInt(4);
				int statusId = res.getInt(5);

				CandidateDto candidateDto = new CandidateDto();
				candidateDto.setCandidateId(candidateId);
				candidateDto.setCandidateName(candidateName);
				candidateDto.setElectionId(electionId);
				candidateDto.setDisplayOrder(displayOrder);
				candidateDto.setStatus(statusId);

				candidates.add(candidateDto);
			}
			validator.setVerified(true);
			validator.setObject(candidates);
			validator.setStatus("Successfully selected");

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setVerified(false);
			validator.setStatus("Database Connection Failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			validator.setVerified(false);
			validator.setStatus("Select failed");
		}
		return validator;
	}

	/**
	 * @param electionIdKey
	 *            - election identification number
	 * @return String : list of all the voters email participating the election (seperated by new line)
	 *         of a given election
	 * @author Hirosh Wickramasuriya
	 */
	private String selectParticipatingVotersOfElection(int electionIdKey) {
		
		PreparedStatement st = null;

		String query = "SELECT p.user_id, p.election_id, email "
				+ " FROM participate p"
				+ " INNER JOIN users u"
				+ " ON (p.user_id = u.user_id)"
				+ " WHERE election_id = ?"
				+ " GROUP BY p.user_id"
				;
		String currentEmailList = "";
		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, electionIdKey);

			ResultSet res = st.executeQuery();

			while (res.next()) {
				currentEmailList += res.getString(3) + newLine;
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);			
		}

		return currentEmailList;
	}
	/**
	 * @param name
	 *            - election name Add new election to db
	 * @author Steven Frink
	 */
	private int addElectionWithCandidatesString(ElectionDto electionDto) {
		PreparedStatement st = null;
		ResultSet rs = null;
		int newId = 0;
		SecurityValidator sec=new SecurityValidator();

		try {
			
			String query = "INSERT INTO election "
					+ " (election_name, description, status, owner_id, candidates_string, start_datetime, close_datetime, type, allowed_users_emails, public_key, private_key)"
					+ " VALUES (?,		?,				?,		?,		?,					?,				?,				?, 		?,					?,		?)";
			
			Validator keyVal=sec.generateKeyPair();
			if(keyVal.isVerified()){
				byte[] pk=((ArrayList<byte[]>)keyVal.getObject()).get(0);
				byte[] sk=DataEncryptor.AESEncrypt(((ArrayList<byte[]>)keyVal.getObject()).get(1), 
						electionDto.getPassword());
				
				
				st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
				
				st.setString(1, electionDto.getElectionName());
				st.setString(2,  electionDto.getElectionDescription());
				st.setInt(3, ElectionStatus.NEW.getCode());
				st.setInt(4, electionDto.getOwnerId());
				st.setString(5, electionDto.getCandidatesListString());
				st.setString(6, electionDto.getStartDatetime());
				st.setString(7, electionDto.getCloseDatetime());
				st.setInt(8, electionDto.getElectionType());
				if (electionDto.getElectionType() == ElectionType.PRIVATE.getCode()) {
					st.setString(9, electionDto.getRegisteredEmailList());
				} else {
					st.setString(9, "");
				}
				Blob bpk=new SerialBlob(pk);
				st.setBlob(10, bpk);
				Blob bsk = new SerialBlob(sk);
				st.setBlob(11,bsk);
				// update query
				st.executeUpdate();
				// get inserted id
				rs = st.getGeneratedKeys();
				if (rs.next() ) {
					newId = rs.getInt(1);
				}
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);	
		} catch (Exception e){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, e.getMessage(), e);	
		}
		

		return newId;
	}
	
	/**
	 * Adds election record to the election table
	 * @param electionDto   - election details
	 * @return Validator 	- with ElectionDto object with primary key assigned by the db, upon successful insert
	 * @author Hirosh Wickramasuriya
	 */
	public Validator addElection(ElectionDto electionDto) {
		Validator val = new Validator();
		
		// Validate the election
		Validator vElection = electionDto.Validate();
		boolean valid = true;
		if (vElection.isVerified()) {
			
			int electionId = 0;
			// For private elections, check whether the given email addresses are registered 
			if (electionDto.getElectionType() == ElectionType.PRIVATE.getCode()) {
				// private election - check all the emails
				Validator vEmailList = checkUserEmails(electionDto);
				
				ElectionDto electionDtoEmailChecked = (ElectionDto)vEmailList.getObject();
				electionDto.setRegisteredEmailList(electionDtoEmailChecked.getRegisteredEmailList());
				electionDto.setUnregisteredEmailList(electionDtoEmailChecked.getUnregisteredEmailList());	
				electionDto.setEmailListError(electionDtoEmailChecked.isEmailListError());
				electionDto.setEmailListMessage(electionDtoEmailChecked.getEmailListMessage());
				
				if (!electionDtoEmailChecked.isEmailListError()) {
					electionId = addElectionWithCandidatesString(electionDto);
				
					if ((electionId > 0 ) && (!electionDto.getEmailListInvited().trim().isEmpty())) {
						electionDto.setElectionId(electionId);
						
						// add invited users to the user table
						Validator vAddInvitations = addUserInvitations(electionDto);
						valid &= vAddInvitations.isVerified();
						
					}
				}
			} else if (electionDto.getElectionType() == ElectionType.PUBLIC.getCode()) {
				electionId = addElectionWithCandidatesString(electionDto);
			}
			
			// insert election
			
			if (electionId > 0) {
				electionDto.setElectionId(electionId);
				
				val.setObject(electionDto);
				val.setVerified(valid & !electionDto.isEmailListError());
				val.setStatus("Election has been inserted");	
			} else {
				val.setObject(electionDto);
				val.setVerified(false);
				val.setStatus("Election insert failed");
			}
		} else {
			val.setStatus( vElection.getStatus());
			val.setObject(electionDto); 
		}
		
		return val;
	}


	/**
	 * @param candidateDto
	 *            - candidate object
	 * @param election_id
	 *            - id of the election which the candidate should be associated
	 * @return Validator - status of the candidate insert operation
	 * @author Hirosh Wickramasuriya
	 */
	private Validator addCandidate(CandidateDto candidateDto) {
		PreparedStatement st = null;
		ResultSet rs = null;
		Validator val = new Validator();
		int newCandidateId = 0;

		try {

			String query = "INSERT INTO candidate (candidate_name, election_id, status, display_order) VALUES (?,?,?,?)";
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, candidateDto.getCandidateName());
			st.setInt(2, candidateDto.getElectionId());
			st.setInt(3, Status.ENABLED.getCode());
			st.setInt(4, candidateDto.getDisplayOrder());

			// run the query and get new candidate id
			st.executeUpdate();
			rs = st.getGeneratedKeys();
			rs.next();
			newCandidateId = rs.getInt(1);
			if (newCandidateId > 0) {
				candidateDto.setCandidateId(newCandidateId);
				val.setVerified(true);
				val.setStatus("Candidates inserted successfully");
				val.setObject(candidateDto);
			} else {
				val.setStatus("Failed to insert candidate");
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("SQL Error");
		}

		return val;
	}

	/**
	 * Updates an election 
	 * @param electionDto
	 *            - election data object
	 * @return validator - status of election update operation
	 * @author Hirosh / Dmitry
	 */
	public Validator editElection(ElectionDto electionDto) {

		Validator val = new Validator();

		// check the election status.
		ElectionDto vElectionCurrent = (ElectionDto) selectElectionForVoter(electionDto.getElectionId()).getObject();
		if (vElectionCurrent.getStatus() == ElectionStatus.NEW.getCode())
		{
			// Validate Election
			Validator vElection = electionDto.Validate();

			if (vElection.isVerified()) {
				// For private elections, check whether the given email addresses are registered 
				
				Validator vEditElection = new Validator();
				if (electionDto.getElectionType() == ElectionType.PRIVATE.getCode()) {
					// private election - check all the emails
					Validator vEmailList = checkUserEmails(electionDto);
					
					ElectionDto electionDtoEmailChecked = (ElectionDto)vEmailList.getObject();
					electionDto.setRegisteredEmailList(electionDtoEmailChecked.getRegisteredEmailList());
					electionDto.setUnregisteredEmailList(electionDtoEmailChecked.getUnregisteredEmailList());	
					electionDto.setEmailListError(electionDtoEmailChecked.isEmailListError());
					electionDto.setEmailListMessage(electionDtoEmailChecked.getEmailListMessage());
					
					if (!electionDtoEmailChecked.isEmailListError()) {
						// Update the election details if all email good for private election
						vEditElection = editElectionWithCandidatesString(electionDto);
						
						if (!electionDto.getEmailListInvited().trim().isEmpty()) {
							// add invited users to the user table
							Validator vAddInvitations = addUserInvitations(electionDto);
							vEditElection = vAddInvitations;
						}
					}
					
				} else if (electionDto.getElectionType() == ElectionType.PUBLIC.getCode()) {
					// updated the election details of public election
					vEditElection = editElectionWithCandidatesString(electionDto);
				}
				
				val.setObject(electionDto);
				val.setStatus(vEditElection.getStatus());
				val.setVerified(vEditElection.isVerified() & !electionDto.isEmailListError());
			} else {
				val = vElection;
				val.setObject(electionDto);
			}
		} else { 
			val.setObject(electionDto);
			val.setStatus("Election status is " + vElectionCurrent.getStatusCode() + ", does not allow to modify.");
		}
		return val;
	}

	/**
	 * open the election while populating the candidates table and participate table with candidates and
	 * invited users respectively
	 * @param electionDto
	 *            - election data object
	 * @return validator - status of election update operation
	 * @author Hirosh / Dmitry
	 */
	public Validator openElectionAndPopulateCandidates(int electionId) {

		Validator val = new Validator();
		Validator vElectionInDb = selectElectionFullDetail(electionId);
		
		if (vElectionInDb.isVerified()) {
			// Retrieve the election object in the db
			ElectionDto electionInDb = (ElectionDto)vElectionInDb.getObject();
		
			Validator vElectionStatus = compareElectionStatus(electionInDb, ElectionStatus.NEW);
			if (vElectionStatus.isVerified()) {
				// Validate the election, so that all the candidates get validated, 
				// and also the list of email account for private election
				Validator vElection = electionInDb.Validate();
				if (vElection.isVerified()) {
					// remove if there are any candidates already for this election
					deleteCandidates( electionInDb.getElectionId() );
					
					// add the list of candidates 
					Validator vAddCandidates = addCandidates(electionId, electionInDb.getCandidatesListString());
					if (vAddCandidates.isVerified()) {
						val = addAllowedUsersAndOpenElection(electionInDb);
					} else {
						val = vAddCandidates;
					}
				} else {
					val= vElection;
				}
			} else {
				val = vElectionStatus;
			}
		} else {
			val = vElectionInDb;
		}
		return val;
	}
	
	/**
	 * If Private election add users to participate table
	 * change the election status  to OPEN
	 * @param ElectionDto 
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	private Validator addAllowedUsersAndOpenElection(ElectionDto electionInDb){
		Validator val = new Validator();
		// add allowed users for private elections
		int electionId = electionInDb.getElectionId();
		boolean isGoodToOpen = false;
		if (electionInDb.getElectionType() == ElectionType.PRIVATE.getCode()) {
			// private election
			// add allowed users to the participate table
			Validator vAddUsers = addAllowedUsers(electionInDb);
			if (vAddUsers.isVerified()) {
				
				isGoodToOpen = true;	// can change the status of election to OPEN
			} else {
				// remove the candidates already added
				deleteCandidates( electionInDb.getElectionId() );
				val = vAddUsers;
			}
		} else if (electionInDb.getElectionType() == ElectionType.PUBLIC.getCode()){
			// public election, 
			isGoodToOpen = true;		// can change the status of election to OPEN
		}
		
		if (isGoodToOpen) {
			// change the status of election to OPEN
			val = editElectionStatus(electionId, ElectionStatus.OPEN);
			val.setStatus("Election has been opened.");
		}
		return val;
	}
	
	
	/** send email to all the users associated with this election
	 * @param String electionName
	 * @param String emailListString 
	 * @author Hirosh Wickramasuriya
	 */
	private void notifyUsersByEmail(String electionName, String emailListString){
		
		// send email to all the users associated with this election
		String[] emailList = emailListString.split(newLine);
		for (String email : emailList){
			if (email.trim().isEmpty()) {
				continue;
			}
			UserDto userDto  = selectUserByEmailLimited(email);
			if (userDto.getUserId() == 0) {
				// no user found
				continue;
			}
			
			if (userDto.getStatus() == UserStatus.ACTIVE.getCode()) {
				// Only the active users should receive the email to vote
				String messageSubject = EmailExchanger.getNotificationSubject();
				String messageBody = EmailExchanger.getNotificationBody(userDto, electionName);
			
				EmailExchanger.sendEmail(email, messageSubject, messageBody);	
			}
		}
	}
	
	/**
	 * @param int electionId
	 * @param String candidatesListString seperated by newLine 
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	private Validator addCandidates(int electionId, String candidatesListString)
	{
		Validator val = new Validator();
		
		// split the list of candidates by new line into an array of string
		String[] candidateNames = candidatesListString.split(newLine);
		int displayOrder = 1;
		boolean status = true;
		for (String candidateName : candidateNames) {
			// add each candidate to this election
			CandidateDto candidateDto = new CandidateDto();
			candidateDto.setCandidateName(candidateName);
			candidateDto.setDisplayOrder(displayOrder);
			candidateDto.setElectionId(electionId);
			
			// add candidate to the election
			Validator vCandiateInserted = addCandidate(candidateDto);
			
			val.setStatus(val.getStatus() + newLine + vCandiateInserted.getStatus());
			status &= vCandiateInserted.isVerified();
			
			displayOrder++;
		}
		val.setVerified(status);
		
		if (status) {
			val.setVerified(true);
			val.setStatus("Candidates have been added to the election");
		}
		return val;
	}
	
	/**
	 * Adds additional users to the election while it is opened for vote
	 * @param electionDto
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	public Validator addAdditionalUsersToElection(ElectionDto electionDto) {
		Validator val = new Validator();
		
		// check the election status.
		ElectionDto electionDtoCurrent = (ElectionDto) selectElectionForOwner(electionDto.getElectionId()).getObject();
		if (electionDtoCurrent.getStatus() == ElectionStatus.OPEN.getCode() ) {
			// Election is in OPEN state
			
			// These properties are set from the db, in order to pass the validation.
			electionDto.setElectionName(electionDtoCurrent.getElectionName());
			electionDto.setElectionDescription(electionDtoCurrent.getElectionDescription());
			electionDto.setElectionType(electionDtoCurrent.getElectionType());
			electionDto.setCandidatesListString(electionDtoCurrent.getCandidatesListString());
			electionDto.setStatus(electionDtoCurrent.getStatus());
			
			// Validate Election
			Validator vElection = electionDto.Validate();
			if (vElection.isVerified()) {
				// For private elections, check whether the given email addresses are registered 
				if (electionDtoCurrent.getElectionType() == ElectionType.PRIVATE.getCode()) {
					// private election - check all the emails
					Validator vEmailList = checkUserEmails(electionDto);
					
					ElectionDto electionDtoEmailChecked = (ElectionDto)vEmailList.getObject();
					electionDto.setRegisteredEmailList(electionDtoEmailChecked.getRegisteredEmailList());
					electionDto.setUnregisteredEmailList(electionDtoEmailChecked.getUnregisteredEmailList());	
					electionDto.setEmailListError(electionDtoEmailChecked.isEmailListError());
					electionDto.setEmailListMessage(electionDtoEmailChecked.getEmailListMessage());
					
					
					val = addAdditionalUserInvitations(electionDto);
					
					// get the current users' email list
					electionDto.setCurrentEmailList(selectParticipatingVotersOfElection(electionDto.getElectionId()));
					val.setObject(electionDto);
				} else {
					// not a private election, do not add users
					val.setStatus("This is not a private election, cannot add new users to this election");
				}
			} else {
				val = vElection;
			}
		} else { 
			val.setStatus("Election status is " + electionDtoCurrent.getStatusCode() + ", does not allow to add new users");
		}
		return val;

	}

	/**
	 * invite additional users to vote
	 * @param electionDto
	 * @return
	 */
	private Validator addAdditionalUserInvitations(ElectionDto electionDto) {
		Validator val = new Validator();
		
		if (!electionDto.isEmailListError()) {
			boolean isOk = true;
			if (!electionDto.getEmailListInvited().trim().isEmpty()) {
				// add invited users to the user table
				Validator vAddInvitations = addUserInvitations(electionDto);
				if (vAddInvitations.isVerified()) {
					// consider invited users as registered users
					String registeredUserEmails = electionDto.getRegisteredEmailList();
					registeredUserEmails += newLine + electionDto.getEmailListInvited().trim();
					electionDto.setRegisteredEmailList(registeredUserEmails);
				}
				isOk = vAddInvitations.isVerified();
			}
			
			if (isOk) {
				// add new users to the participate table if all email good for private election
				Validator vAddUsers = addAllowedUsers(electionDto);
				val = vAddUsers; // Verified status is set to true or false by the previous statement
			}
		} else {
			val.setStatus(electionDto.getEmailListMessage());
		}
		return val;
	}
	
	private Validator addAllowedUsers(ElectionDto electionDto) {
		Validator val = new Validator();
		
		
		String emailListString = electionDto.getRegisteredEmailList();
		
		if (!emailListString.trim().isEmpty()) {
			// split the list of emails by new line into an array of string
			String[] emails = emailListString.split(newLine);
			int electionId = electionDto.getElectionId();
			String electionName = electionDto.getElectionName();
			
			boolean status = true;
			for (String email : emails) {
				email = email.trim();
				if (email.isEmpty()) {
					continue;
				}
				
				// add users to participate table 
				Validator vAddUser = AddAllowedUser(electionId, email, UserType.ELECTORATE);
				
				val.setStatus(val.getStatus() + newLine + vAddUser.getStatus());
				status &= vAddUser.isVerified();
				
				// invite existing users to vote
				UserDto userDto = selectUserByEmailLimited(email);
				if ((userDto != null) && (userDto.getUserId() > 0)) {
					// user exist
					notifyUsersByEmail(electionName, email);
				}
			}
			val.setVerified(status);
			if (status) {
				val.setVerified(true);
				val.setStatus("Users have been allowed to participate the election");
			}
		} else {
			val.setStatus("empty email list detected, failed to add allowed users to the election");
		}
		
		
		return val;
	}
	
	/**
	 * @param electionId - election Id
	 * @param email - email of the user
	 * @param userRole - user role (ELECTORATE)
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	private Validator AddAllowedUser(int electionId, String email, UserType userRole) {
		
		
		Validator val = new Validator();

		PreparedStatement st = null;
		try {

			String query = "INSERT INTO participate "
								+ " (user_id, election_id, role_id) "
								+ " VALUES "
								+ "( "
								+ "	(SELECT user_id FROM users WHERE email = ?) "
								+ " , ?"
								+ " , ? "
								+ ")";
	
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, email);
			st.setInt(2, electionId);
			st.setInt(3, userRole.getCode());
			
			// run the query and get the count of rows updated
			int recInserted = st.executeUpdate();
			
			if (recInserted > 0) {
				// successfully inserted
				val.setVerified(true);
				val.setStatus("User allowed to vote is inserted successfully");

			} else {
				val.setStatus("Failed to insert user : " + email);
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("SQL Error");
		}

		return val;
	}
	
	
	/**
	 * Updates the candidate status
	 * @param CandidateDto cand
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	public Validator editCandidateStatus(CandidateDto cand) {
		PreparedStatement st = null;
		//InputValidation iv = new InputValidation();
		Validator val = new Validator();
		try {
			//val = iv.validateInt(cand.getStatus(), "Candidate Status");
			if (val.isVerified()) {
				String query = "UPDATE candidate SET status=? WHERE candidate_id=?";
				st = this.con.prepareStatement(query);
				st.setInt(1, cand.getStatus());
				st.setInt(2, cand.getCandidateId());
				
				st.execute();
				
				val.setVerified(true);
				val.setStatus("Candidate status updated");
			} else {
				val.setStatus("Status failed to verify");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}

	/**
	 * @param electionId 	- election identification number
	 * @return boolean 		- true : if the election is deleted successfully, else false
	 * @author Hirosh Wickramasuriya
	 */
	private boolean deleteCandidates(int electionId) {
		PreparedStatement st = null;
		boolean status = false;

		try {
			String query = "DELETE FROM candidate WHERE election_id = ?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, electionId);

			// update query
			if (st.executeUpdate() < 0) {
				// delete failed

			} else {
				// delete= sucessful
				status = true;
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}
		return status;
	}

	/**
	 * Updates an election status
	 * @param electionId
	 *            - the election id to update the status Delete an election
	 * @param electionStatus
	 *            - new status of the election
	 * @return validator - validator object with response of update operation
	 * @author Steven Frink
	 */
	public Validator editElectionStatus(int electionId, ElectionStatus electionStatus) {
		PreparedStatement st = null;
		Validator val = new Validator();
		try {
			String query = "";
			if (electionStatus.equals(ElectionStatus.OPEN)) {
				query = "UPDATE election SET status=?, allowed_users_emails = null WHERE election_id=?";
			} else {
				query = "UPDATE election SET status=? WHERE election_id=?";
			}
			
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, electionStatus.getCode());
			st.setInt(2, electionId);
			
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				val.setStatus("Election status updated successfully");
				val.setVerified(true);
			} else {
				val.setStatus("Failed to update the election status");
			}
			
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}
	
	/**
	 * Mark election for deletion
	 * @param electionId
	 * @return Validator
	 * @author Hirosh 
	 */
	public Validator deleteElection(int electionId) {
		PreparedStatement st = null;
		Validator val = new Validator();
		try {
			String query = "UPDATE election SET status=? WHERE election_id=?";
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, ElectionStatus.DELETED.getCode());
			st.setInt(2, electionId);
			
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				val.setStatus("Election status updated successfully");
				val.setVerified(true);
			} else {
				val.setStatus("Failed to delete the election");
			}
			
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}

	/**
	 * @param electionDto
	 *            - the election to edit Edit an election
	 * @author Steven Frink
	 */
	private Validator editElectionWithCandidatesString(ElectionDto electionDto) {
		PreparedStatement st = null;

		Validator val = new Validator();
		try {
			String query = "UPDATE election SET election_name = ? "
					+ " , description = ? "
					+ " , candidates_string = ? "
					+ " , start_datetime = ? "
					+ " , close_datetime = ? "
					+ " , type = ?"
					+ " , allowed_users_emails = ? "
					+ " WHERE election_id=?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, electionDto.getElectionName());
			st.setString(2, electionDto.getElectionDescription());
			st.setString(3, electionDto.getCandidatesListString());
			st.setString(4, electionDto.getStartDatetime());
			st.setString(5, electionDto.getCloseDatetime());
			st.setInt(6, electionDto.getElectionType());
			if (electionDto.getElectionType() == ElectionType.PRIVATE.getCode()) {
				st.setString(7, electionDto.getRegisteredEmailList());
			} else {
				st.setString(7, "");
			}
			st.setInt(8, electionDto.getElectionId());
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				val.setStatus("Election updated successfully");
				val.setVerified(true);
			} else {
				val.setStatus("Failed to update the election");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}

		return val;
	}


	/**
	 * Records the vote 
	 * @param voteDto
	 *            - the vote to submit Submit a vote
	 * @return Validator
	 * @author Steven Frink
	 */

	public Validator vote(VoteDto voteDto) {
		PreparedStatement st = null;
		Validator val = new Validator();

		if (voteDto.Validate().isVerified())
		{
			try {
				String query = "SELECT user_id, election_id FROM vote WHERE user_id=? AND election_id=?";
				st = this.con.prepareStatement(query);
				st.setInt(1, voteDto.getUserId());
				st.setInt(2, voteDto.getElectionId());
				ResultSet rs = st.executeQuery();
				SecurityValidator sec = new SecurityValidator();
				if (!rs.next()
						&& sec.checkSignature(voteDto.getVoteSignature(), voteDto.getVoteEncrypted(),
								voteDto.getUserId()).isVerified()) {
					query = "INSERT INTO vote (user_id, election_id, vote_encrypted, vote_signature)"
							+ " VALUES (?,?,?,?)";
					st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
					st.setInt(1, voteDto.getUserId());
					st.setInt(2, voteDto.getElectionId());
					st.setString(3, voteDto.getVoteEncrypted());
					st.setString(4, voteDto.getVoteSignature());

					int updateCount = st.executeUpdate();
					if (updateCount > 0) {
						val.setStatus("Vote successfully cast");
						val.setVerified(true);
					} else {
						val.setStatus("Failed to cast vote");
					}

				} else {
					voteDto.setVoteSignatureError(true);
					voteDto.setVoteSignatureErrorMessage("invalid signature for this vote");
					val.setObject(voteDto);
					val.setStatus("invalid signature for this vote");
				}
			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				val.setStatus("SQL Error");
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				val.setStatus("SQL Error");
			}
		} else {
			val.setStatus("Vote information did not validate");
		}

		return val;
	}

	/**
	 * Selects users public key
	 * @param userDto
	 *            - userDto with the userId
	 * @return Validator - with user's public key
	 * @author Steven Frink
	 */
	public Validator selectUserPublicKey(UserDto userDto) {
		PreparedStatement st = null;
		Validator val = new Validator();

		InputValidation iv = new InputValidation();
		Validator vUserDto = iv.validateInt(userDto.getUserId(), "User ID");

		// Validator vUserDto = userDto.Validate();
		if (vUserDto.isVerified()) {
			String query = "SELECT public_key FROM users WHERE user_id = ?";

			try {
				st = this.con.prepareStatement(query);
				st.setInt(1, userDto.getUserId());
				ResultSet res = st.executeQuery();
				if (res.next()) {
					Blob pubKey = res.getBlob(1);
					byte[] pk = pubKey.getBytes(1, (int) pubKey.length());
					val.setObject(pk);
					val.setVerified(true);
					val.setStatus("Public key retrieved");

				} else {
					val.setStatus("No public key for this user id");
				}

			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				val.setStatus("SQL Error");
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				val.setStatus("SQL Error");
			}
		} else {
			val = vUserDto; // Failed to validate the user id
		}

		return val;
	}

	/**
	 * Selects votes by election Id
	 * @param election_id
	 * @return Validator
	 * 
	 */
	public Validator selectVotesByElectionId(int election_id) {
		Validator val = new Validator();
		InputValidation iv = new InputValidation();
		ArrayList<VoteDto> votes = new ArrayList<VoteDto>();
		Validator vElection = iv.validateInt(election_id, "Election ID");
		PreparedStatement st = null;
		try {
			if (vElection.isVerified()) {
				String query = "SELECT user_id, vote_encrypted, vote_signature, timestamp " + "	FROM vote "
						+ " WHERE election_id = ?";
				st = this.con.prepareStatement(query);
				st.setInt(1, election_id);
				ResultSet res = st.executeQuery();
				while (res.next()) {
					int user_id = res.getInt(1);
					String vote_encrypted = res.getString(2);
					String vote_signature = res.getString(3);
					Timestamp t = res.getTimestamp(4);

					VoteDto vote = new VoteDto();
					vote.setUserId(user_id);
					vote.setVoteEncrypted(vote_encrypted);
					vote.setVoteSignature(vote_signature);
					vote.setElectionId(election_id);
					vote.setTimestamp(t);

					votes.add(vote);
				}
				val.setStatus("Successfully retrieved votes");
				val.setObject(votes);
				val.setVerified(true);
			} else {
				val = vElection; // Failed to validate the election id
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}
	
	/**
	 * Emails addresses (separated by new line) in ElectionDto.emailList are verified,
	 * registered  emails are saved to ElectionDto.registeredEmails
	 * unregistered emails are saved to ElectionDto.unregisteredEmails
	 * @param electionDto
	 * @return Validator with electionDto object
	 * @author Hirosh Wickramasuriya
	 */
	private Validator checkUserEmails(ElectionDto electionDto){
		Validator val=new Validator();
		
		String registeredEmails = "";
		String unregisteredEmails = "";
		boolean allRegisteredEmails = true;
		
		if (electionDto.getEmailList() != null) {
			String[] emails = electionDto.getEmailList().split(newLine);
			for (String email : emails) {
				String emailTrimmed = email.trim();
				if (!emailTrimmed.isEmpty()) {
					UserDto userDto = selectUserByEmailLimited(emailTrimmed);
					if (userDto != null && userDto.getUserId() > 0) {
						if (registeredEmails.indexOf(emailTrimmed) == -1) {
							registeredEmails += emailTrimmed + newLine;
						}
					} else {
						if (unregisteredEmails.indexOf(emailTrimmed) == -1) {
							unregisteredEmails += emailTrimmed + newLine;
							allRegisteredEmails = false;
						}
					}
				}
			}
		}
		
		electionDto.setRegisteredEmailList(registeredEmails);
		electionDto.setUnregisteredEmailList(unregisteredEmails);
		electionDto.setEmailListError(!allRegisteredEmails);
		if (allRegisteredEmails) {
			val.setVerified(true);
			val.setStatus("All email addresses are valid");
		} else {
			val.setVerified(false);
			val.setStatus("Unregistered email addresses detected");
			electionDto.setEmailListMessage("Unregistered email addresses detected");
		}
		val.setObject(electionDto);
		
		return val;
	}
	private Map<Integer, CandidateDto> initMap(ElectionDto elec){
		Map<Integer, CandidateDto> map = new HashMap<Integer, CandidateDto>();
		
		// initialize the hashmap to have all the candidates
		for (CandidateDto candidate : elec.getCandidateList()) {
			map.put(candidate.getCandidateId(), candidate);
		}
		return map;
	}
	
	private Map<Integer, CandidateDto> addToMap(Map<Integer, CandidateDto> map, int cand_id){
		if (map.containsKey(cand_id)) {
			// candidateDto is in the Hashmap
			CandidateDto candidateDto = map.get(cand_id);
			candidateDto.addVoteCount();

			// replace the candidateDto in the Hashmap
			map.remove(cand_id);
			map.put(cand_id, candidateDto); // TODO: not sure without these twolines,
											// value is udpated by reference

		} else {
			// this is a new candidateDto to the Hashmap
			CandidateDto candidateDto = (CandidateDto) selectCandidate(cand_id).getObject();
			candidateDto.setVoteCount(1); // First voted counted
			map.put(cand_id, candidateDto);
		}
		return map;
	}
	
	private ElectionDto putResultsInElection(Map<Integer, CandidateDto> map, ElectionDto e){
		ArrayList<CandidateDto> candidateResultList = new ArrayList<CandidateDto>();
		Iterator<Integer> iterator = map.keySet().iterator();

		while (iterator.hasNext()) {
			Integer key = iterator.next();
			CandidateDto candidateResult = map.get(key);
			candidateResultList.add(candidateResult);
		}
		e.setCandidateList(candidateResultList);
		return e;
		
	}
	
	private int getDecryptedCandId(VoteDto vote, PrivateKey privateKey){
		int out = -1;
		
		String enc = vote.getVoteEncrypted();
		String sig = vote.getVoteSignature();
		SecurityValidator sec=new SecurityValidator();
		if (sec.checkSignature(sig, enc, vote.getUserId()).isVerified()) {
			Validator vDecryptVote = sec.decryptVote(enc, privateKey);

			if(vDecryptVote.isVerified()) {
				out = (Integer) vDecryptVote.getObject();				
			}			
		}
		
		return out;
	}
	
	/**
	 * tally the election results
	 * @param electionId
	 * @return Validator with ElectionDto that has results
	 * @author Steven Frink/Hirosh Wickramasuriya
	 */
	public Validator tally(ElectionDto elec) {
		Validator val = new Validator();
		Validator vKey = getElectionPrivateKey(elec.getElectionId(), elec.getPassword());
		
		if(vKey.isVerified()) {
			PrivateKey privateKey = (PrivateKey) vKey.getObject();
			
			// get the votes for this election 
			Validator voteVal = selectVotesByElectionId(elec.getElectionId());
	
			if (voteVal.isVerified()) {
				Map<Integer, CandidateDto> map=initMap(elec);
				ArrayList<VoteDto> votes = (ArrayList<VoteDto>) voteVal.getObject();			// all the votes for the election
				
				// check the validity of each vote, decrypt and count the vote
				for (int i = 0; i < votes.size(); i++) {
					int cand_id=getDecryptedCandId(votes.get(i), privateKey);	
					if (cand_id!=-1) {
						map=addToMap(map, cand_id);
					}
				}
				// attach the candidates list with results to the ElectionDto
				elec=putResultsInElection(map, elec);
	
				val.setStatus("Tally computed");
				val.setObject(elec);
				val.setVerified(true);
			} else {
				val = voteVal;
			}
		} else {
			elec.setPasswordError(true);
			elec.setPasswordErrorMessage("Invalid password");
			vKey.setObject(elec);
			val = vKey;
		}
		
		return val;
	}

	/**
	 * Returns the Number of votes collected
	 * @param electionId
	 * @return Validator with ElectionProgressDto
	 * @author Hirosh Wickramasuriya
	 */
	public Validator voteProgressStatusForElection(int electionId)
	{
		Validator val = new Validator();

		SecurityValidator sec = new SecurityValidator();
		ElectionProgressDto electionProgressDto = new ElectionProgressDto();

		if (electionId > 0) {
			electionProgressDto.setElectionId(electionId);
			Validator valVote = selectVotesByElectionId(electionId);

			if (valVote.isVerified()) {
				ArrayList<VoteDto> votes = (ArrayList<VoteDto>) valVote.getObject();
				electionProgressDto.setTotalVotes(votes.size());

				for (VoteDto voteDto : votes) {

					// check for the validity
					if (sec.checkSignature(voteDto).isVerified()) {
						// valid vote
						electionProgressDto.addValidVotes(1);

					} else {
						// rejected vote
						electionProgressDto.addRejectedVotes(1);
					}
				}

				// bind the final result to the validator
				val.setObject(electionProgressDto);
				val.setStatus("Election progress computed");
				val.setVerified(true);

			} else {
				val = valVote;
			}

		} else {
			val.setStatus("Invalid Election Id");
		}

		return val;
	}

	/**
	 * publish election results - populate the results table and change the election status to PUBLISHED
	 * @param electionId
	 * @param password
	 * @return Validator
	 * @author Steven / Hirosh
	 */
	public Validator publishResults(int electionId, String password) {
		Validator val = new Validator();
		Validator vElectionStatus = compareElectionStatus(electionId, ElectionStatus.CLOSED);
		if (vElectionStatus.isVerified()) {
			Validator vResult = computeElectionResults(electionId, password);
			
			if (vResult.isVerified()) {
				vElectionStatus = editElectionStatus(electionId, ElectionStatus.PUBLISHED);
				if (vElectionStatus.isVerified()) {
					val.setStatus("Election results has been published");
					val.setVerified(true);
				} else {
					val = vElectionStatus;
				}

			} else {
				val = vResult;
			}
		} else {
			val = vElectionStatus;
		}

		return val;
	}

	/**
	 * @param electionId - election identificatin number
	 * @return Validator - 	(1) true if the election results computed and the table is populated successfully
	 * 						(2) false if it failed to compute and populate the election results
	 * @author Hirosh Wickramasuriya
	 */
	private Validator computeElectionResults(int electionId, String password) {
		Validator val = new Validator();

		// check the election status
		Validator vElectionStatus = compareElectionStatus(electionId, ElectionStatus.CLOSED);
		if (vElectionStatus.isVerified()) {
		
			ElectionDto electionDto = (ElectionDto)vElectionStatus.getObject();
			electionDto.setPassword(password);
			// get the tallying results
			Validator vElectionTally = tally(electionDto);

			if (vElectionTally.isVerified()) {
				// Get the results for each candidates
				electionDto = (ElectionDto)vElectionTally.getObject();
				ArrayList<CandidateDto> candidates = electionDto.getCandidateList();
				boolean valid = true;
				
				for (CandidateDto candidate : candidates) {			
					if (addResult(candidate) > 0) {		
						valid &= true;									// result has been added
					} else {
						// Failed to add the result			
						deleteResults(electionDto.getElectionId()); 	// delete existing results if any

						val.setStatus("Failed to add results");			// set the validator
						valid &= false;
						break;
					}
				}

				val.setVerified(valid);
				if (valid) {
					val.setStatus("Results added successfully");
				} else {
					val.setStatus("Failed to add results");
				}
			} else {
				val = vElectionTally;
			}
		} else {
			val = vElectionStatus;
		}
		return val;
	}
	
	/**
	 * @param ElectionDto		- Election object
	 * @param electionStatus 	- ElectionStatus enumerated value
	 * @return Validator 		- the property isVerified() contains whether the given status 
	 * matches to the status of the given electionDto 
	 * @author Hirosh Wickramasuriya
	 */
	private Validator compareElectionStatus(ElectionDto electionDto, ElectionStatus electionStatus)
	{
		Validator val = new Validator();
		if (electionDto.getStatus() == electionStatus.getCode()) {
			val.setObject(electionDto);
			val.setStatus("Status matched");
			val.setVerified(true);
		} else {
			val.setStatus("Election is not in the " + electionStatus.getLabel() + " status");
		}
		
		return val;
	}
	
	/**
	 * @param electionId		- Election identification number
	 * @param electionStatus 	- ElectionStatus enumerated value
	 * @return Validator 		- the property isVerified() contains whether the given status 
	 * matches to the status recorded in the database for the given election id
	 * @author Hirosh Wickramasuriya
	 */
	private Validator compareElectionStatus(int electionId, ElectionStatus electionStatus)
	{
		Validator val = new Validator();
		Validator vElection = selectElectionForOwner(electionId);
		
		if (vElection.isVerified()) {
			val = compareElectionStatus((ElectionDto)vElection.getObject(), electionStatus );
		} else {
			val = vElection;
		}
		
		return val;
	}
	
	
	/**
	 * @param candidateDto - candiate object to be added to the results table
	 * @return id of the inserted result record
	 * @author Hirosh Wickramasuriya
	 */
	private int addResult(CandidateDto candidateDto) {

		PreparedStatement st = null;
		ResultSet rs = null;
		int newId = 0;

		try {
			String query = "INSERT INTO results (election_id, candidate_id, vote_count) VALUES (?,?,?)";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, candidateDto.getElectionId());
			st.setInt(2, candidateDto.getCandidateId());
			st.setInt(3, candidateDto.getVoteCount());

			// update query
			st.executeUpdate();
			// get inserted id
			rs = st.getGeneratedKeys();
			rs.next();
			newId = rs.getInt(1);

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}

		return newId;
	}

	/**
	 * @param electionId 	- election identification number
	 * @return boolean 		- true : if the election is deleted successfully, else false
	 * @author Hirosh Wickramasuriya
	 */
	private boolean deleteResults(int electionId) {
		PreparedStatement st = null;
		boolean status = false;

		try {
			String query = "DELETE FROM results WHERE election_id = ?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, electionId);

			// update query
			if (st.executeUpdate() < 0) {
				// delete failed

			} else {
				// delete= sucessful
				status = true;
			}

		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}
		return status;
	}

	/**
	 * selects elections results from the results table
	 * @param electionId 	- election identification number
	 * @return Validator 	- with ElectionDto having results of each candidates
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectResults(int electionId) {
		Validator val = new Validator();
		ArrayList<CandidateDto> candidates = new ArrayList<CandidateDto>();
		PreparedStatement st = null;
		Validator vElectionStatus = compareElectionStatus(electionId, ElectionStatus.PUBLISHED);
		
		if (vElectionStatus.isVerified()) {
			ElectionDto electionDto = (ElectionDto)vElectionStatus.getObject();
			
			try {
				String query = "SELECT r.election_id, r.candidate_id, vote_count, candidate_name, display_order, status"
						+ " FROM results r"
						+ " INNER JOIN candidate c"
						+ " ON (r.candidate_id = c.candidate_id)"
						+ " WHERE r.election_id = ?";

				int maxVote = 0;
				st = this.con.prepareStatement(query);
				st.setInt(1, electionId);
				ResultSet res = st.executeQuery();
				
				while (res.next()) {

					int resElectionId = res.getInt(1);
					int resCandidateId = res.getInt(2);
					int resVoteCount = res.getInt(3);
					String resCandiateName = res.getString(4);
					int resDisplayOrder = res.getInt(5);
					int resStatus = res.getInt(6);

					// populate candidates list
					CandidateDto candidateDto = new CandidateDto();
					candidateDto.setCandidateId(resCandidateId);
					candidateDto.setCandidateName(resCandiateName);
					candidateDto.setElectionId(resElectionId);
					candidateDto.setDisplayOrder(resDisplayOrder);
					candidateDto.setVoteCount(resVoteCount);
					candidateDto.setStatus(resStatus);
					
					// indicate the winning candidate
					if (resVoteCount > maxVote) {
						for (CandidateDto candidate : candidates) {
							candidate.setWinner(false);
						}
						candidateDto.setWinner(true);
						maxVote = resVoteCount;
					
					} else if ( (resVoteCount == maxVote)  && (resVoteCount >0)) {
						candidateDto.setWinner(true);
					}
					candidates.add(candidateDto);
				}

				electionDto.setCandidateList(candidates); 	// attach candidates list to the election

				// set the validator
				val.setVerified(true);
				val.setObject(electionDto);
				val.setStatus("Results selected successfully");

			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				val.setStatus("Select failed");
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				val.setStatus("Select failed");
			}
		} else {
			val = vElectionStatus;
		}
		return val;
	}

	
	/**
	 * add invited users to the user table and update the election with 
	 * @param electionDto
	 * @return Validator
	 * @author Hirosh Wickramasuriya
	 */
	private Validator addUserInvitations(ElectionDto electionDto) {
		Validator val = new Validator();
		String status = "";
		boolean valid = true;
		
		String[] emailList = electionDto.getEmailListInvited().split(newLine);
		String 	addedEmails = "";
	
		// Get the election name from the database (to include into the email)
		ElectionDto electionInDb = (ElectionDto)selectElectionFullDetail(electionDto.getElectionId()).getObject();
		
		if (electionInDb.getElectionType() == ElectionType.PRIVATE.getCode()){
			for (String email : emailList) {
				// do nothing if blank email
				if (email.trim().isEmpty()) { continue; }
				
				// do nothing if user already exist
				UserDto uTemp = selectUserByEmailLimited(email.trim());
				if(uTemp != null && uTemp.getUserId() > 0) {
					continue;
				}
				
				// add a user with a temporary password
				Validator vInviteUser = addUserInvitation(email); 
				
				if (vInviteUser.isVerified()) {
					// add this user to the addedEmail list, so that the election.allowed_users_email could be updated.
					addedEmails += email + newLine;
					
					// send email invitation to the user
					UserDto user = (UserDto)vInviteUser.getObject();
					user.setEmail(email);
					inviteUserByEmail(user);	

				} else {
					valid &= vInviteUser.isVerified();
					status += vInviteUser.getStatus() + newLine;
				}
			}

			if (electionInDb.getStatus() == ElectionStatus.NEW.getCode()) {
				Validator vUpdateElection = editElectionWithNewlyAddedEmails(electionInDb, addedEmails);
				valid &= vUpdateElection.isVerified();
				status += vUpdateElection.getStatus() + newLine;
			} 
			
			
			if (valid) {
				status = "Users invited for the election successfully";
			}
		} else {
			valid = false;
			status = "Election type [" + ElectionType.getStatus(electionInDb.getElectionType()).getLabel() + "] is invalid to invite users";
		}
		
		val.setStatus(status);
		val.setVerified(valid);
		return val;
	}
	
	
	private Validator editElectionWithNewlyAddedEmails(ElectionDto electionDto, String addedEmails) {
		Validator val = new Validator();
		if (electionDto.getStatus() == ElectionStatus.NEW.getCode()) {
			// update the election.allowed_users_email with new list of emails
			if (!addedEmails.trim().isEmpty()) {
				String newEmailList = electionDto.getRegisteredEmailList() + newLine;
				newEmailList += addedEmails;
				
				ElectionDto electionDtoUpdate = electionDto;
				electionDtoUpdate.setEmailList(newEmailList);
				val = editElection(electionDtoUpdate);
			}
		}
		
		return val;
	}
	
	private void inviteUserByEmail(UserDto user) {
		// send email invitation to the user
		String messageSubject = EmailExchanger.getInvitationSubject();
		String messageBody = EmailExchanger.getInvitationBody(user);
		
		EmailExchanger.sendEmail(user.getEmail(), messageSubject, messageBody);	
	}
	
	private Validator addUserInvitation(String emailInvited) {
		Validator val = new Validator();
		
		PreparedStatement st = null;
		ResultSet rs = null;
		int newUserId = 0;
		
		// temporary salt
		String salt = PasswordHasher.generateSalt();
		// temporary password 
		String password = PasswordHasher.generateRandomString();
		//hash the password:
		String hashedPass = PasswordHasher.sha512(password, salt);
		
		
		// add user  with temp password and email the password.
		String query = "INSERT INTO users (email, type, password, salt, status) "
				+ " VALUES (?, ?, ?, ?, ?)";
		try {
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, emailInvited);
			st.setInt(2, UserType.INVITED.getCode());
			st.setString(3, hashedPass);
			st.setString(4, salt);
			st.setInt(5, Status.ENABLED.getCode());
			
			// run the query and get new user id
			st.executeUpdate();
			rs = st.getGeneratedKeys();
			rs.next();
			newUserId = rs.getInt(1);
			if (newUserId > 0) {
				UserDto userDto = new UserDto();
				userDto.setPassword(password);
				userDto.setEmail(emailInvited);
				userDto.setUserId(newUserId);
				
				val.setVerified(true);
				val.setStatus("User inserted successfully");
				val.setObject(userDto);
			} else {
				val.setStatus("Failed to insert user");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			
			val.setStatus("SQL Error");
		}
		
		return val;
	}
	
	/**
	 * Selects all the users in teh system
	 * @return - Validator with ArrayList<UserDto>  all the users in the system
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectAllUsers()
	{
		Validator val = new Validator();

		ArrayList<UserDto> users = new ArrayList<UserDto>();
		PreparedStatement st = null;

		String query = "SELECT user_id, first_name, last_name, email "
				+ " , u.status, s.description "
				+ " FROM users u"
				+ " INNER JOIN status_user s"
				+ " ON (u.status = s.status_id)"
				+ " ORDER BY user_id";
				
		try {
			st = this.con.prepareStatement(query);
			
			ResultSet res = st.executeQuery();

			while (res.next()) {
				UserDto userDto = new UserDto();
				userDto.setUserId(res.getInt(1));
				userDto.setFirstName(res.getString(2));
				userDto.setLastName(res.getString(3));
				userDto.setEmail(res.getString(4));
				userDto.setStatus(res.getInt(5));
				userDto.setStatusDescription(res.getString(6));
				users.add(userDto);
			}
			val.setStatus("Retrieved Users");
			val.setVerified(true);
			val.setObject(users);
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("Select failed");
		}

		return val;
	}
	
	/**
	 * Retrieve user information by user id
	 * @param - userId - user identificaiton number 
	 * @return - Validator with UserDto containing user information for the given user id
	 * @author Hirosh Wickramasuriya
	 */
	public Validator selectUser(int userId)
	{
		Validator val = new Validator();

		UserDto userDto = new UserDto();
		PreparedStatement st = null;

		String query = "SELECT user_id, first_name, last_name, email "
				+ " , u.status, s.description "
				+ " FROM users u"
				+ " INNER JOIN status_user s"
				+ " ON (u.status = s.status_id)"
				+ " WHERE user_id = ?";
		
				
		try {
			st = this.con.prepareStatement(query);
			st.setInt(1, userId);
			
			ResultSet res = st.executeQuery();

			if (res.next()) {
				userDto.setUserId(res.getInt(1));
				userDto.setFirstName(res.getString(2));
				userDto.setLastName(res.getString(3));
				userDto.setEmail(res.getString(4));
				userDto.setStatus(res.getInt(5));
				userDto.setStatusDescription(res.getString(6));
				
				val.setStatus("Retrieved user information");
				val.setVerified(true);
				val.setObject(userDto);
			} else {
				val.setStatus("User not found ");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("Select failed");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("Select failed");
		}

		return val;
	}
	
	/**
	 * Updates user details
	 * @param userDto
	 * @return with the verified status true upon successful update, false otherwise
	 * @author Hirosh Wickramasuriya
	 */
	public Validator editUser(UserDto userDto) {
		Validator val = new Validator();
		
		PreparedStatement st = null;
		try {
			String query = "UPDATE users SET first_name = ?,"
					+ " last_name = ?,"
					+ " status = ? "
					+ " WHERE user_id=?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, userDto.getFirstName());
			st.setString(2, userDto.getLastName());
			st.setInt(3, userDto.getStatus());
			st.setInt(4, userDto.getUserId());
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				val.setStatus("User updated successfully");
				val.setVerified(true);
			} else {
				val.setStatus("Failed to update the user");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}

	/**
	 * @param userId
	 * @param userStatus - UserStatus enumeration value
	 * @return Validator with status true upon successful update, false otherwise
	 * @author Hirosh Wickramasuriya
	 */
	public Validator editUserStatus(int userId, UserStatus userStatus){
		Validator val = new Validator();
		PreparedStatement st = null;
		try {
			String query = "UPDATE users  SET status = ? WHERE user_id=?";
				
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, userStatus.getCode());
			st.setInt(2, userId);
			
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				val.setStatus("User status updated successfully");
				val.setVerified(true);
			} else {
				val.setStatus("Failed to update the user status");
			}
			
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}
	
	
	/**
	 * get user role by the user id
	 * @param userID
	 * @return Validator
	 */
	public Validator getUserRoleByID(int userID){
		
		Validator val = new Validator();
		PreparedStatement st = null;
		
		try{
			String query = "SELECT type from users where (user_id = ?)";
			
			st = con.prepareStatement(query);
			st.setInt(1, userID);
			ResultSet res = st.executeQuery();

			if (res.next()) {
				val.setVerified(true);
				val.setStatus("User found.");
				val.setObject(res.getInt(1));
			}else{
				val.setVerified(false);
				val.setStatus("User not found.");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		} catch (SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		}
		
		return val;
	}
	
 
	/**
	 * check if a user role is permitted to do an action:
	 * @param roleID
	 * @param actionID
	 * @return Validator
	 */
	public Validator checkRoleRight(int roleID, int actionID){
		
		Validator val = new Validator();
		PreparedStatement st = null;
		
		try{
			String query = "SELECT * from role_rights where ((role_id = ? ) && (action_id = ?))";
			
			st = con.prepareStatement(query);
			st.setInt(1, roleID);
			st.setInt(2, actionID);
			ResultSet res = st.executeQuery();

			if (res.next()) {
				val.setVerified(true);
				val.setStatus("User role is allowed to invoke action.");
			}else{
				val.setVerified(false);
				val.setStatus("User role is not allowed to invoke action.");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		} catch (SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		}
		
		return val;		
	}
	

	/**
	 * get action id by the method name
	 * @param methodName
	 * @return Validator
	 */
	public Validator getActionIDbyMethod(String methodName){
		Validator val = new Validator();
		PreparedStatement st = null;
		
		try{
			String query = "SELECT action_id from actions where (method_name = ?)";
			
			st = con.prepareStatement(query);
			st.setString(1, methodName);
			
			ResultSet res = st.executeQuery();

			if (res.next()) {
				val.setVerified(true);
				val.setStatus("Method name found.");
				val.setObject(res.getInt(1));
			}else{
				val.setVerified(false);
				val.setStatus("Method name is not found.");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		} catch (SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		}
		
		return val;				
	}
	
	
	/**
	 * get all the rights allowed for a user role
	 * @param roleID
	 * @return Validator
	 */
	public Validator getRoleRights(int roleID){
		Validator val = new Validator();
		PreparedStatement st = null;
		ArrayList<ActionDto> rightsListArray = new ArrayList<ActionDto>();
		
		
		try{
			String query = "SELECT action_id FROM role_rights where (role_id = ?)";
			
			st = con.prepareStatement(query);
			st.setInt(1, roleID);
			
			ResultSet res = st.executeQuery();

			
			while (res.next()){
				ActionDto action = new ActionDto();
				action.setActionID(res.getInt(1));
				rightsListArray.add(action);
			}
			
			if (rightsListArray.size() != 0){
				val.setVerified(true);
				val.setStatus("Rights found.");
				val.setObject(rightsListArray);
			}else{
				val.setVerified(false);
				val.setStatus("No rights found.");
			}
			
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		} catch (SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");			
		}
		
		return val;						
	}
	

	/**
	 * register new user with basic information (Firstname, Lastname, Email, Password):
	 * @param UserDto - newUser object
	 * @return Validator
	 */
	public Validator addUser(UserDto newUser){
		Validator res = new Validator();
		if (checkUserEmail(newUser.getEmail()).isVerified()){
			//email found, cannot add user:
			res.setVerified(false);
			res.setStatus("E-mail address is used.");
		}else{
			//email address is not found, let's add it:
			
			//first we need to generate some salt to hash the password:
			String salt = PasswordHasher.generateSalt();
			
			//hash the password:
			String hashedPass = PasswordHasher.sha512(newUser.getPassword(), salt);
			
			//let's generate the keys and protect the 
			//private key with the users login password:
			String keyPass = newUser.getPassword();
			RSAKeys rsaKeys = new RSAKeys();
			rsaKeys.generateKeys(keyPass);
			
			//get the public key to be saved at the DB:
			PublicKey pubKey = rsaKeys.getPublicKey();
			
			
			//ready to push to DB:
			PreparedStatement st = null;
			ResultSet rs = null;
			int newUserId = 0;
			// Validate the user
			Validator vUser = newUser.Validate();
			
			if (vUser.isVerified()) {
				// insert user
				String query = "INSERT INTO users (first_name, last_name, email, password, salt, "
						+ "public_key, type, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
				try {
					st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
					st.setString(1, newUser.getFirstName());
					st.setString(2, newUser.getLastName());
					st.setString(3, newUser.getEmail());
					st.setString(4, hashedPass);
					st.setString(5, salt);
					Blob pubKeyBlob = new SerialBlob(pubKey.getEncoded());
					st.setBlob(6, pubKeyBlob);
					st.setInt(7, UserType.ELECTORATE.getCode());
					st.setInt(8, UserStatus.ACTIVE.getCode());
		
					// run the query and get new user id
					st.executeUpdate();
					rs = st.getGeneratedKeys();
					rs.next();
					newUserId = rs.getInt(1);
					if (newUserId > 0) {
						res.setObject(newUserId);
						res.setVerified(true);
						res.setStatus("User inserted successfully");
						
						//send the private key as an email:
						rsaKeys.sendProtectedPrivateKey(newUser.getEmail());
					} else {
						res.setStatus("Failed to insert user");
					}
				} catch (MySQLNonTransientConnectionException ex) {
					reconnectToDb();
					Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
					lgr.log(Level.WARNING, ex.getMessage(), ex);
					res.setStatus("SQL Error");
				} catch (SQLException ex) {
					Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
					lgr.log(Level.WARNING, ex.getMessage(), ex);
					res.setVerified(false);
					res.setStatus("SQL Error");
				}
			} else {
				res = vUser;
			}
		}
		return res;
	}
	
	/**
	 * get email address by user ID
	 * Validator
	 * @param userID
	 * @return Validator
	 */
	public Validator getUserEmail (int userID){
		Validator res = new Validator();
		PreparedStatement st = null;
		ResultSet rs = null;

					   
		String query = "select email from users WHERE user_id=?";
		try {
			
			st = con.prepareStatement(query);
			st.setInt(1, userID);
			rs = st.executeQuery();
			
			if(rs.next()){
				res.setVerified(true);		
				res.setStatus(rs.getString(1));
			}else{
				res.setVerified(false);
				res.setStatus("User not found");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
		}
		
		return res;
	}
	

	/**
	 * Generates pair of keys for a specific user and updates the user table with public key and 
	 * email the private key to the user
	 * @param userID
	 * @param newKeyPass
	 * @param userPassword
	 * @return Validator
	 */
	public Validator generateNewKeys(int userID, String newKeyPass, String userPassword){
		Validator res = new Validator();
		
		//Check if the userPassword is correct:
		if (!checkCorrectPassword(userID, userPassword)){
			res.setVerified(false);
			res.setStatus("Wrong password");
			return res;
		}
		
		
		res = getUserEmail(userID);
		
		if (res.isVerified()){
			String email = res.getStatus();
			
			//let's generate the keys and protect the private key with the users protecion password:
			RSAKeys rsaKeys = new RSAKeys();
			rsaKeys.generateKeys(newKeyPass);
			
			//get the public key to be saved at the DB:
			PublicKey pubKey = rsaKeys.getPublicKey();
			
			PreparedStatement st = null;

			String query = "UPDATE users SET public_key=? WHERE user_id=?";
			try {
				
				st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
				Blob pubKeyBlob = new SerialBlob(pubKey.getEncoded());
				st.setBlob(1, pubKeyBlob);
				st.setInt(2, userID);
				
				st.executeUpdate();
				int updateCount = st.getUpdateCount();
				if (updateCount > 0) {
					res.setStatus("User updated successfully");
					res.setVerified(true);
				} else {
					res.setVerified(false);
					res.setStatus("Failed to update the user");
				}
				
				//send the private key as an email:
				rsaKeys.sendProtectedPrivateKey(email);				
				
			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
			}
		}else{
			res.setVerified(false);
			res.setStatus("User not found");
		}
				
		return res;
	}
	
	
	/**
	 * Update user information - this function to be invked only by the users
	 * @param userDto
	 * @return Validator
	 */
	public Validator updateUser(UserDto userDto) {
		Validator val = new Validator();
		
		PreparedStatement st = null;
		try {
			String query = "UPDATE users SET first_name = ?, last_name = ? WHERE user_id=?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, userDto.getFirstName());
			st.setString(2, userDto.getLastName());
			st.setInt(3, userDto.getUserId());
			
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				val.setStatus("User updated successfully");
				val.setVerified(true);
			} else {
				val.setStatus("Failed to update the user");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setStatus("SQL Error");
		}
		return val;
	}

	/**
	 * Checks whether the password is correct for the given userID
	 * @param userID
	 * @param password
	 * @return boolean - true or false
	 */
	public boolean checkCorrectPassword(int userID, String password){		
		UserDto dbUser = new UserDto();
		dbUser = selectUserById(userID);
		String dbHash = dbUser.getPassword();
		String dbSalt = dbUser.getSalt();
		String newHash = PasswordHasher.sha512(password, dbSalt);
		
		return newHash.equals(dbHash);
	}

	/**
	 * Updates the user password
	 * @param UserDto - userInfo user object
	 * @return Validator
	 */
	public Validator updateUserPassword(UserDto userInfo){
		Validator res = new Validator();
		
		String newPass = userInfo.getTempPassword();
		int userID = userInfo.getUserId();
		
		//first we need to generate some salt to hash the password:
		String newSalt = PasswordHasher.generateSalt();
		
		//hash the password:
		String hashedPass = PasswordHasher.sha512(newPass, newSalt);
		
		//ready to update the password:
		PreparedStatement st = null;
		try {
			String query = "UPDATE users SET password = ?, salt = ? WHERE user_id=?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, hashedPass);
			st.setString(2, newSalt);
			st.setInt(3, userID);
			
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				
				res.setStatus("Password updated successfully");
				res.setVerified(true);
				res.setObject(userInfo);
			} else {
				res.setStatus("Failed to update password");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res.setStatus("SQL Error");
		}

		return res;
		
	}
	
	public Validator updateUserPassword(UserDto userInfo, String newPassword){
		Validator res = new Validator();
		
		int userID = userInfo.getUserId();
		
		//first we need to generate some salt to hash the password:
		String newSalt = PasswordHasher.generateSalt();
		
		//hash the password:
		String hashedPass = PasswordHasher.sha512(newPassword, newSalt);
		
		//ready to update the password:
		PreparedStatement st = null;
		try {
			String query = "UPDATE users SET password = ?, salt = ? WHERE user_id=?";

			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			st.setString(1, hashedPass);
			st.setString(2, newSalt);
			st.setInt(3, userID);
			
			st.executeUpdate();
			
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				
				res.setStatus("Password updated successfully");
				res.setVerified(true);
				res.setObject(userInfo);
			} else {
				res.setStatus("Failed to update password");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res.setStatus("SQL Error");
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res.setStatus("SQL Error");
		}

		return res;
		
	}

	/**
	 * Upload public keys for user
	 * @param keyBytes
	 * @param userID
	 * @param userPassword
	 * @return Validator
	 */
	public Validator uploadPubKey(byte[] keyBytes, int userID, String userPassword) {
		Validator res = new Validator();
				
		//Check if the userPassword is correct:
		if (!checkCorrectPassword(userID, userPassword)){
			res.setVerified(false);
			res.setStatus("Wrong password");
			return res;
		}

		PreparedStatement st = null;
		
		if (keyBytes == null){
			res.setVerified(false);
			res.setStatus("Empty input");
			return res;
		}
		
		if(!RSAKeys.isValidPublicKey(keyBytes)){
			res.setVerified(false);
			res.setStatus("Invalid public key");
			return res;
		}
		
		String query = "UPDATE users SET public_key=? WHERE user_id=?";
		try {
			
			st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
			
			Blob pubKeyBlob = new SerialBlob(keyBytes);
			st.setBlob(1, pubKeyBlob);
			st.setInt(2, userID);
			
			st.executeUpdate();
			int updateCount = st.getUpdateCount();
			if (updateCount > 0) {
				res.setStatus("Public key updated successfully");
				res.setVerified(true);
			} else {
				res.setVerified(false);
				res.setStatus("Failed to update public key");
			}
			
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res.setStatus("SQL Exception");
		} catch (SQLException ex) {
			ex.printStackTrace();
			res.setVerified(false);
			res.setStatus("SQL Exception");
		}
		return res;
	}

	
	/**
	 * Check if election is public
	 * @param electionID
	 * @return
	 */
	public boolean isPublicElection(int electionID){
		boolean res = false;
		
		PreparedStatement st = null;
		String query = "SELECT type FROM election WHERE election_id=?";

		try {
			st = con.prepareStatement(query);
			st.setInt(1, electionID);
			ResultSet rs = st.executeQuery();
			
			if (rs.next()){
				int type = rs.getInt(1);
				res = (type == 1) ? true : false;
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		}		
		
		
		return res;
	}
	
	/**
	 * Check if userID got access to elecionID
	 * @param userID
	 * @param electionID
	 * @return boolean
	 */
	public boolean gotAccessToElection(int userID, int electionID){
		boolean res = false;
		
		if (isPublicElection(electionID)){
			return true;
		}
		
		if(isElectionAuth(userID, electionID)) {
			return true;
		}
		
		PreparedStatement st = null;
		String query = "SELECT * FROM participate WHERE (user_id = ?) and (election_id=?)";

		try {
			st = con.prepareStatement(query);
			st.setInt(1, userID);
			st.setInt(2, electionID);
			ResultSet rs = st.executeQuery();
			res = (rs.next()) ? true : false;	
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		}		
		
		return res;
	}
	
	
	/**
	 * Check if userID is election authority on electionID
	 * @param userID
	 * @param electionID
	 * @return boolean
	 */
	public boolean isElectionAuth(int userID, int electionID){
		boolean res = false;
		
		PreparedStatement st = null;
		String query = "SELECT * FROM election WHERE (election_id = ?) and (owner_id=?)";

		try {
			st = con.prepareStatement(query);
			st.setInt(1, electionID);
			st.setInt(2, userID);
			ResultSet rs = st.executeQuery();
			res = (rs.next()) ? true : false;	
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		}		

		return res;
	}
	
	/**
	 * Gets the public key of the election for tallying 
	 * @param electionId
	 * @return
	 */
	public Validator getTallierPublicKey(int electionId){
		Validator val=new Validator();
		PreparedStatement st = null;
		String query="SELECT public_key FROM election WHERE election_id=?";
		try{
			st = this.con.prepareStatement(query);
			st.setInt(1,electionId);
			ResultSet rs=st.executeQuery();
			if(rs.next()){
				Blob pubKey = rs.getBlob(1);
				byte[] pk = pubKey.getBytes(1, (int) pubKey.length());
				PublicKey pub = KeyFactory.getInstance("RSA").
						generatePublic(new X509EncodedKeySpec(pk));
				val.setObject(pub);
				val.setVerified(true);
				val.setStatus("Public key retrieved");
			}
			else{
				val.setVerified(false);
				val.setStatus("Public key does not exist for this election");
			}
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Failed to retrieve public key");
		} catch(SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Failed to retrieve public key");
		} catch(Exception ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Key in incorrect binary format");
		}
		return val;
	}
	
	/**
	 * Gets the private key of the election
	 * @param electionId
	 * @return Validator
	 * @author Steven
	 */
	public Validator getPrivateKey(int electionId){
		Validator val=new Validator();
		PreparedStatement st=null;
		String query="SELECT private_key FROM election WHERE election_id=?";
		try{
			st=this.con.prepareStatement(query);
			st.setInt(1,electionId);
			ResultSet rs=st.executeQuery();
			if(rs.next()){
				Blob privKey = rs.getBlob(1);
				byte[] sk = privKey.getBytes(1, (int) privKey.length());
				val.setObject(sk);
				val.setVerified(true);
				val.setStatus("Private key retrieved");
			}
			else{
				val.setVerified(false);
				val.setStatus("Private key does not exist for this election");
			}
		} catch(MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Failed to retrieve private key");
		} catch(SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Failed to retrieve private key");
		}
		return val;
	}

	
	/**
	 * check if requesterID is invited
	 * @param requesterID
	 * @param electionID
	 * @return boolean
	 */
	public boolean isInvited(int requesterID, int electionID) {
		boolean res = false;
		
		if(isPublicElection(electionID)) {
			return true;
		}
		
		PreparedStatement st = null;
		String query = "SELECT * FROM participate WHERE (election_id = ?) and (user_id=?)";

		try {
			st = con.prepareStatement(query);
			st.setInt(1, electionID);
			st.setInt(2, requesterID);
			ResultSet rs = st.executeQuery();
			res = (rs.next()) ? true : false;	
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			res = false;
		}		
		
		return res;
	}
	
	
	
	/**
	 * Gets the private key of the election
	 * @param electionId
	 * @param password
	 * @return Validator
	 */
	public Validator getElectionPrivateKey(int electionId, String password) {
		Validator vKey = new Validator();
		Validator vKeyDb = getPrivateKey(electionId);
		
		
		if(vKeyDb.isVerified()) {			
			// key from DB has been fetched
			byte[] keyBytesEncrypted = (byte[]) vKeyDb.getObject();

			try {
				byte[] keyBytesDecrypted = DataEncryptor.AESDecrypt(keyBytesEncrypted, password);
				vKey = RSAKeys.getPrivateKey(keyBytesDecrypted);
			} catch (Exception e) {
				vKey.setVerified(false);
				vKey.setStatus("Invalid password");
			}
		} else {
			vKey.setVerified(false);
			vKey.setStatus("Election key could not be fetched");
		}
		
		return vKey;
	}
	
	/**
	 * Sets temporary password for user
	 * @param u
	 * @param temp
	 * @param salt
	 * @return
	 */
	public Validator setTempPassword(UserDto u, String temp, String salt){
		Validator val = new Validator();
		
		PreparedStatement st = null;
		try {
			String query = "UPDATE users SET temp_password = ?,"
					+ " temp_salt=? WHERE email=?";
			st = this.con.prepareStatement(query);
			st.setString(1, temp);
			st.setString(2, salt);
			st.setString(3,u.getEmail());
			st.execute();
			val.setStatus("Updated temp password");
			val.setVerified(true);
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Failed to add temp password");
		} catch(SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("Failed to add temp password");
		}
		return val;
	}
	
	/**
	 * Checks whether the temporary password is correct for the specified user account
	 * then change the users current password to a newPassword.
	 * If the password is correct then the temp password is removed from DB.
	 * @param email
	 * @param plainPass
	 * @param newPassword
	 * @return Validator
	 * @author Steven
	 */
	public Validator checkIfUsernameTempPasswordMatch(String email, String plainPass, String newPassword){
		// 1. validate input
		Validator result = validateEmailAndPlainInput(email, plainPass);
		if (!result.isVerified()) {
			return result;
		}
		
		// 2. validate email
		result = checkUserEmail(email);
		if (!result.isVerified()) {
			return result;
		}

		// get this user limited info from the database
		UserDto userDto = selectUserByEmailLimited(email);

		String dbHash = userDto.getTempPassword();
		String dbSalt = userDto.getTempSalt();
		if(dbHash==""||dbHash==null){
			result.setVerified(false);
			result.setStatus("No temporary password in the database");
			return result;
		}
		
		String plainHash = PasswordHasher.sha512(plainPass, dbSalt);

		// 3. if entered password is correct, return true with welcome message
		if (plainHash.equals(dbHash)) {
			userDto.setTempPassword(newPassword);
			updateUserPassword(userDto);
			result.setObject(userDto);
			result.setVerified(true);
			result.setStatus("Welcome to Certus");
			removeTempPassword(userDto);
			return result;
		} else {
			result.setVerified(false);
			result.setStatus("Error, the system could not resolve the provided combination of username and password.");
			return result;
		}
	}

	/**
	 * register new user with basic information (Firstname, Lastname, Email, Password)
	 * and with a dedicated password to protect the private key:
	 * @param UserDto - newUser object
	 * @return Validator 
	 */
	public Validator addUserWithPP(UserDto newUser){
		Validator res = new Validator();
		if (checkUserEmail(newUser.getEmail()).isVerified()){
			//email found, cannot add user:
			res.setVerified(false);
			res.setStatus("E-mail address is used.");
		}else{
			//email address is not found, let's add it:
			
			//first we need to generate some salt to hash the password:
			String salt = PasswordHasher.generateSalt();
			
			//hash the password:
			String hashedPass = PasswordHasher.sha512(newUser.getPassword(), salt);
			
			//let's generate the keys and protect the 
			//private key with the users protection password:
			String keyPass = newUser.getTempPassword();
			RSAKeys rsaKeys = new RSAKeys();
			rsaKeys.generateKeys(keyPass);
			
			//get the public key to be saved at the DB:
			PublicKey pubKey = rsaKeys.getPublicKey();
			
			
			//ready to push to DB:
			PreparedStatement st = null;
			ResultSet rs = null;
			int newUserId = 0;
			// Validate the user
			Validator vUser = newUser.Validate();
			
			if (vUser.isVerified()) {
				// insert user
				String query = "INSERT INTO users (first_name, last_name, email, password, salt, "
						+ "public_key, type, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
				try {
					st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
					st.setString(1, newUser.getFirstName());
					st.setString(2, newUser.getLastName());
					st.setString(3, newUser.getEmail());
					st.setString(4, hashedPass);
					st.setString(5, salt);
					Blob pubKeyBlob = new SerialBlob(pubKey.getEncoded());
					st.setBlob(6, pubKeyBlob);
					st.setInt(7, UserType.ELECTORATE.getCode());
					st.setInt(8, UserStatus.ACTIVE.getCode());
					// run the query and get new user id
					st.executeUpdate();
					rs = st.getGeneratedKeys();
					rs.next();
					newUserId = rs.getInt(1);
					if (newUserId > 0) {
						newUser.setUserId(newUserId);
						res.setVerified(true);
						res.setStatus("User inserted successfully");
						
						//send the private key as an email:
						rsaKeys.sendProtectedPrivateKey(newUser.getEmail());
					} else {
						res.setStatus("Failed to insert user");
					}
				} catch (MySQLNonTransientConnectionException ex) {
					reconnectToDb();
					Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
					lgr.log(Level.WARNING, ex.getMessage(), ex);
					res.setVerified(false);
					res.setStatus("SQL Error");
				} catch (SQLException ex) {
					Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
					lgr.log(Level.WARNING, ex.getMessage(), ex);
					res.setVerified(false);
					res.setStatus("SQL Error");
				}
			} else {
				res = vUser;
			}
		}
		return res;
	}


	/**
	 * register new user with basic information(Firstname, Lastname, Email, Password) 
	 * and with the user uploaded public key:
	 * @param UserDto - newUser object
	 * @return Validator 
	 */
	public Validator addUserWithKey(UserDto newUser){
		Validator res = new Validator();
		if (checkUserEmail(newUser.getEmail()).isVerified()){
			//email found, cannot add user:
			res.setVerified(false);
			res.setStatus("E-mail address is used.");
		}else{
			//email address is not found, let's add it:
			
			//let's check if the attached public key is valid or not:
			if(!RSAKeys.isValidPublicKey(newUser.getPublicKeyBytes())){
				res.setVerified(false);
				res.setStatus("Invalid public key");
				return res;
			}
			
			//first we need to generate some salt to hash the password:
			String salt = PasswordHasher.generateSalt();
			
			//hash the password:
			String hashedPass = PasswordHasher.sha512(newUser.getPassword(), salt);
						
			//ready to push to DB:
			PreparedStatement st = null;
			ResultSet rs = null;
			int newUserId = 0;
			// Validate the user
			Validator vUser = newUser.Validate();
			
			if (vUser.isVerified()) {
				// insert user
				String query = "INSERT INTO users (first_name, last_name, email, password, salt, "
						+ "public_key, type, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
				try {
					st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
					st.setString(1, newUser.getFirstName());
					st.setString(2, newUser.getLastName());
					st.setString(3, newUser.getEmail());
					st.setString(4, hashedPass);
					st.setString(5, salt);
					Blob pubKeyBlob = new SerialBlob(newUser.getPublicKeyBytes());
					st.setBlob(6, pubKeyBlob);
					st.setInt(7, UserType.ELECTORATE.getCode());
					st.setInt(8, UserStatus.ACTIVE.getCode());
					// run the query and get new user id
					st.executeUpdate();
					rs = st.getGeneratedKeys();
					rs.next();
					newUserId = rs.getInt(1);
					if (newUserId > 0) {
						newUser.setUserId(newUserId);
						res.setVerified(true);
						res.setStatus("User inserted successfully");
						
					} else {
						res.setStatus("Failed to insert user");
					}
				} catch (MySQLNonTransientConnectionException ex) {
					reconnectToDb();
					Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
					lgr.log(Level.WARNING, ex.getMessage(), ex);
					res.setVerified(false);
					res.setStatus("SQL Error");
				} catch (SQLException ex) {
					Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
					lgr.log(Level.WARNING, ex.getMessage(), ex);
					res.setVerified(false);
					res.setStatus("SQL Error");
				}
			} else {
				res = vUser;
			}
		}
		return res;
	}
	
	/**
	 * @author Steven
	 * @param u User to have temp password deleted
	 * Deletes temp password from database for specified user
	 */
	private Validator removeTempPassword(UserDto u){
		PreparedStatement st = null;
		Validator val = new Validator();
		String query = "UPDATE users SET temp_password=?, temp_salt=? WHERE user_id=?";

		try {
			st = this.con.prepareStatement(query);
			st.setString(1,"");
			st.setString(2, "");
			st.setInt(3, u.getUserId());
			st.execute();
			val.setVerified(true);
			val.setStatus("Temporary password removed");
		} catch (MySQLNonTransientConnectionException ex) {
			reconnectToDb();
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("SQL Error");
		} catch(SQLException ex){
			Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
			lgr.log(Level.WARNING, ex.getMessage(), ex);
			val.setVerified(false);
			val.setStatus("SQL Error");
		}
		return val;
	}


	/**
	 * Update temp user with basic information Firstname, lastname, password
	 * @param user
	 * @param tempPassword
	 * @return Validator
	 */
	public Validator updateTempUser(UserDto user){
		Validator res = new Validator();
		
		//We need to generate some salt to hash the new password:
		String salt = PasswordHasher.generateSalt();
		
		//hash the new password:
		String hashedPass = PasswordHasher.sha512(user.getPassword(), salt);
		
		//let's generate the keys and protect the 
		//private key with the users login password:
		String keyPass = user.getPassword();
		RSAKeys rsaKeys = new RSAKeys();
		rsaKeys.generateKeys(keyPass);
		
		//get the public key to be saved at the DB:
		PublicKey pubKey = rsaKeys.getPublicKey();
		
		
		//ready to update the DB:
		PreparedStatement st = null;
		
		// Validate the user
		Validator vUser = user.Validate();
		
		if (vUser.isVerified()) {
			// Update user
			String query = "UPDATE users SET first_name=?, last_name=?, password=?, salt=?, public_key=?, type='0' WHERE user_id=?;";
			try {
				st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
				st.setString(1, user.getFirstName());
				st.setString(2, user.getLastName());
				st.setString(3, hashedPass);
				st.setString(4, salt);
				Blob pubKeyBlob = new SerialBlob(pubKey.getEncoded());
				st.setBlob(5, pubKeyBlob);
				st.setInt(6, user.getUserId());
	
				// run the query and get new user id
				st.executeUpdate();
				
				int updateCount = st.getUpdateCount();
				if (updateCount > 0) {
					res.setVerified(true);
					res.setStatus("User inserted successfully");
					
					//send the private key as an email:
					rsaKeys.sendProtectedPrivateKey(user.getEmail());
				} else {
					res.setStatus("Failed to update user");
				}
			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				res.setVerified(false);
				res.setStatus("SQL Error");
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
			}
		} else {
			res = vUser;
		}
	
	return res;

	}

	
	
	/**
	 * Update temp user with basic information:Firstname, lastname, password.
	 * and the user specific password to protect the private key
	 * @param user
	 * @param tempPassword
	 * @return Validator
	 */
	public Validator UpdateTempUserWithPP(UserDto user){
		Validator res = new Validator();
		
		//We need to generate some salt to hash the new password:
		String salt = PasswordHasher.generateSalt();
		
		//hash the new password:
		String hashedPass = PasswordHasher.sha512(user.getPassword(), salt);
		
		//let's generate the keys and protect the 
		//private key with the users protection password:
		String keyPass = user.getTempPassword();
		RSAKeys rsaKeys = new RSAKeys();
		rsaKeys.generateKeys(keyPass);
		
		//get the public key to be saved at the DB:
		PublicKey pubKey = rsaKeys.getPublicKey();
		
		
		//ready to update the DB:
		PreparedStatement st = null;
		
		// Validate the user
		Validator vUser = user.Validate();
		
		if (vUser.isVerified()) {
			// Update user
			String query = "UPDATE users SET first_name=?, last_name=?, password=?, salt=?, public_key=?, type='0' WHERE user_id=?;";
			try {
				st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
				st.setString(1, user.getFirstName());
				st.setString(2, user.getLastName());
				st.setString(3, hashedPass);
				st.setString(4, salt);
				Blob pubKeyBlob = new SerialBlob(pubKey.getEncoded());
				st.setBlob(5, pubKeyBlob);
				st.setInt(6, user.getUserId());
	
				// run the query and get new user id
				st.executeUpdate();
				
				int updateCount = st.getUpdateCount();
				if (updateCount > 0) {
					res.setVerified(true);
					res.setStatus("User inserted successfully");
					
					//send the private key as an email:
					rsaKeys.sendProtectedPrivateKey(user.getEmail());
				} else {
					res.setStatus("Failed to update user");
				}
			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				res.setVerified(false);
				res.setStatus("SQL Error");
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				res.setVerified(false);
				res.setStatus("SQL Error");
			}
		} else {
			res = vUser;
		}
	
	return res;

	}

	/**
	 * Update temp user with basic information Firstname, lastname, password
	 * and the user public key
	 * 
	 * @param user
	 * @param tempPassword
	 * @return Validator
	 */
	public Validator UpdateTempUserWithKey(UserDto user){
		Validator res = new Validator();
		
		//Check if the attached public key is in the right format:
		if(!RSAKeys.isValidPublicKey(user.getPublicKeyBytes())){
			res.setVerified(false);
			res.setStatus("Invalid public key");
			return res;
		}
		
		
		//We need to generate some salt to hash the new password:
		String salt = PasswordHasher.generateSalt();
		
		//hash the new password:
		String hashedPass = PasswordHasher.sha512(user.getPassword(), salt);
		
				
		//ready to update the DB:
		PreparedStatement st = null;
		
		// Validate the user
		Validator vUser = user.Validate();
		
		if (vUser.isVerified()) {
			// Update user
			String query = "UPDATE users SET first_name=?, last_name=?, password=?, salt=?, public_key=?, type='0' WHERE user_id=?;";
			try {
				st = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
				st.setString(1, user.getFirstName());
				st.setString(2, user.getLastName());
				st.setString(3, hashedPass);
				st.setString(4, salt);
				Blob pubKeyBlob = new SerialBlob(user.getPublicKeyBytes());
				st.setBlob(5, pubKeyBlob);
				st.setInt(6, user.getUserId());
	
				// run the query and get new user id
				st.executeUpdate();
				
				int updateCount = st.getUpdateCount();
				if (updateCount > 0) {
					res.setVerified(true);
					res.setStatus("User inserted successfully");
				} else {
					res.setStatus("Failed to update user");
				}
			} catch (MySQLNonTransientConnectionException ex) {
				reconnectToDb();
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				res.setVerified(false);
				res.setStatus("SQL Error");
			} catch (SQLException ex) {
				Logger lgr = Logger.getLogger(DatabaseConnector.class.getName());
				lgr.log(Level.WARNING, ex.getMessage(), ex);
				res.setVerified(false);
				res.setStatus("SQL Error");
			}
		} else {
			res = vUser;
		}
	
	return res;

	}
}
