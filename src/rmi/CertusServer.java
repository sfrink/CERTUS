package rmi;


import java.rmi.RMISecurityManager;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;

import server.ConfigurationProperties;
import database.DatabaseConnector;
import dto.CandidateDto;
import dto.ElectionDto;
import dto.Validator;
import dto.VoteDto;
import enumeration.Status;
import enumeration.ElectionStatus;


public class CertusServer extends UnicastRemoteObject implements ServerInterface {

    private static int PORT;
    private static DatabaseConnector dbc;

    
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
    public Validator selectElections() throws RemoteException{
    	return dbc.selectElections();
    }
    
    @Override
    public Validator selectElectionsOwnedByUser(int election_owner_id, ElectionStatus electionStatus) throws RemoteException{
    	return dbc.selectElectionsOwnedByUser(election_owner_id, electionStatus);
    }
    
    @Override
    public Validator selectElectionsOwnedByUser(int election_owner_id) throws RemoteException{
    	return  dbc.selectElectionsOwnedByUser(election_owner_id);
    }
    
    @Override
    public Validator addElection(String name, int owner_id) throws RemoteException{
    	Validator validator = new Validator();
    	ElectionDto elec=new ElectionDto();
    	elec.setElection_name(name);
    	elec.setOwner_id(owner_id);
    	
    	return dbc.createNewElection(elec);
    }
    
    @Override
    public Validator editElection(ElectionDto election) throws RemoteException{
       	return dbc.editElection(election);
    }
    
    public Validator deleteElection(int election_id) throws RemoteException{
    	return dbc.deleteElection(election_id);
    	
    }
    
    // Candidate
    
    @Override
    public Validator getCandidate(int id) throws RemoteException{
    	return dbc.selectCandidate(id);
 
    }
    
    @Override
    public Validator getCandidatesOfElection(int election_id) throws RemoteException{
    	
    	return dbc.selectCandidatesOfElection(election_id);
    }
    
    @Override
    public Validator getCandidatesOfElection(int election_id, Status candidateStatus) throws RemoteException{
    	return  dbc.selectCandidatesOfElection(election_id, candidateStatus);
    }
    
    @Override
    public Validator addCandidates(ArrayList<String> names, int election_id) throws RemoteException{
    	
    	ArrayList<CandidateDto> cands=new ArrayList<CandidateDto>();
    	for(int i=0;i<names.size();i++){
    		CandidateDto cand=new CandidateDto();
    		cand.setCandidate_name(names.get(i));
    		cands.add(cand);
    	}

    	return dbc.addCandidatesToElection(cands, election_id);
    }
    
    @Override
    public Validator editCandidate(CandidateDto candidate) throws RemoteException{
    	return dbc.editCandidate(candidate);
    }
    
    //Vote
    public Validator vote(VoteDto v) throws RemoteException{
    	return dbc.vote(v);
    }
    
}
