package rmi;


import java.io.FileInputStream;
import java.rmi.RMISecurityManager;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.cert.Certificate;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import server.ConfigurationProperties;
import server.SecurityValidator;
import database.DatabaseConnector;
import dto.CandidateDto;
import dto.ElectionDto;
import dto.UserDto;
import dto.Validator;
import dto.VoteDto;
import enumeration.Status;
import enumeration.ElectionStatus;
import enumeration.UserStatus;


public class CertusServer extends UnicastRemoteObject implements ServerInterface {

    private static int PORT;
    private static DatabaseConnector dbc;
    private static SecurityValidator sec;

    
    public CertusServer() throws Exception {
		super(PORT, 
		new RMISSLClientSocketFactory(), 
		new RMISSLServerSocketFactory());
    }



    public static void main(String args[]) {
    	
    	PORT = Integer.parseInt(ConfigurationProperties.rmiPort());
    	String filePath = ConfigurationProperties.rmiBasePath();
		System.setProperty("java.security.policy", filePath + ConfigurationProperties.rmiFilePolicy());
		
		// Create and install a security manager
		if (System.getSecurityManager() == null) {
			System.setSecurityManager(new RMISecurityManager());
		}

		try {
			// Create SSL-based registry
			Registry registry = LocateRegistry.createRegistry(PORT,
			new RMISSLClientSocketFactory(),
			new RMISSLServerSocketFactory());

			CertusServer obj = new CertusServer();

			// Bind this object instance to the name "CertusServer"
			registry.bind(ConfigurationProperties.rmiRegistry(), obj);

			dbc = new DatabaseConnector();
			sec = new SecurityValidator();
			
			System.out.println("Certus Service bound in registry");
		} catch (Exception e) {
			System.out.println("Certus RMI service exception: " + e.getMessage());
			e.printStackTrace();
		}
    }
    
    @Override
    public Validator checkIfUsernamePasswordMatch(String email, String plainPass)  throws RemoteException{
    	//Look up username in db, get salt, password hash
    	//DatabaseConnector db = new DatabaseConnector();
    	Validator validator = dbc.checkIfUsernamePasswordMatch(email, plainPass);
    	
//    	UserDto userDto=selectUserByEmailLimited(username);
//    	String hash=PasswordHasher.sha512(password,userDto.getSalt());
//    	return hash==userDto.getPassword();
    	return validator;
    	
    }
    
    public Validator addUser(UserDto userDto) throws RemoteException {
    	return dbc.addUser(userDto); 
    }
    
    public Validator selectAllUsers() throws RemoteException {
    	return dbc.selectAllUsers();
    }
    
    public Validator editUser(UserDto userDto) throws RemoteException {
    	return dbc.editUser(userDto);
    }
    
    public Validator editUserStatus(int userId, UserStatus userStatus) throws RemoteException {
    	return dbc.editUserStatus(userId, userStatus);
    }
  
    
    public String sayHello(String name) {
		System.out.println("Request received from the client: " + name);
		return "Hello Certus Client: " + name;
    }
    
    // Election
    
    @Override
    public Validator selectElection(int id) throws RemoteException{
    	return dbc.selectElection(id);
    }
    
    @Override
    public Validator selectElections(ElectionStatus electionStatus) throws RemoteException{
    	return dbc.selectElections(electionStatus);
    }
    
    @Override
    public Validator selectElectionsNotInStatus(ElectionStatus electionStatus) throws RemoteException{
    	return dbc.selectElectionsNotInStatus(electionStatus);
    }
    
    @Override
    public Validator selectElections() throws RemoteException{
    	return dbc.selectElections();
    }
    
    @Override
    public Validator selectElectionsOwnedByUser(int election_owner_id, ElectionStatus electionStatus) throws RemoteException{
    	return dbc.selectElectionsOwnedByUser(election_owner_id, electionStatus);
    }
    
    @Override
    public Validator selectElectionsOwnedByUser(int electionOwnerId) throws RemoteException{
    	return  dbc.selectElectionsOwnedByUser(electionOwnerId);
    }
    
    @Override
    public Validator addElection(ElectionDto electionDto)throws RemoteException {
    	return dbc.addElection(electionDto);
    }

    @Override
    public Validator editElection(ElectionDto electionDto)throws RemoteException {
    	return dbc.editElection(electionDto);
    }

    
    @Override
    public Validator editElectionStatus(int electionId, ElectionStatus electionStatus) throws RemoteException{   	
    	return dbc.editElectionStatus(electionId, electionStatus);
    }
    
    @Override
    public Validator openElectionAndPopulateCandidates(int electionId) throws RemoteException {
    	return dbc.openElectionAndPopulateCandidates(electionId);
    }
    
    // Candidate
    @Override
    public Validator selectCandidate(int id) throws RemoteException{
    	return dbc.selectCandidate(id);
 
    }
    
    @Override
    public Validator selectCandidatesOfElection(int electionId) throws RemoteException{
    	
    	return dbc.selectCandidatesOfElection(electionId);
    }
    
    @Override
    public Validator selectCandidatesOfElection(int electionId, Status candidateStatus) throws RemoteException{
    	return  dbc.selectCandidatesOfElection(electionId, candidateStatus);
    }
    
    @Override
    public Validator editCandidateStatus(int candidateId, Status status) throws RemoteException{
    	CandidateDto candidate = new CandidateDto();
    	candidate.setCandidateId(candidateId);
    	candidate.setStatus(status.getCode());
    	return dbc.editCandidateStatus(candidate);
    }

    //Vote
    @Override
    public Validator vote(VoteDto v) throws RemoteException{
    	return dbc.vote(v);
    }
    
    @Override
    public Validator getTallierPublicKey() throws RemoteException{
    	sec=new SecurityValidator();
    	return sec.getTallierPublicKey();
    }
    
    @Override
    public Validator selectAllElectionsForVoter(int user_id) throws RemoteException{
    	return dbc.selectAllElectionsForVoter(user_id);
    }
    
    
    @Override
    public Validator voteProgressStatusForElection(int electionId) throws RemoteException {
    	return dbc.voteProgressStatusForElection(electionId);
    }
    
    @Override
    public Validator publishResults(int electionId) throws RemoteException {   	
    	return dbc.publishResults(electionId);
    }
    
    @Override
    public Validator selectResults(int electionId) throws RemoteException { 
    	return dbc.selectResults(electionId);
    }
    
    
}
