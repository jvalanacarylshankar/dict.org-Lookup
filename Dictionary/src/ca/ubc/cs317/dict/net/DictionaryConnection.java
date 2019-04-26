package ca.ubc.cs317.dict.net;

import ca.ubc.cs317.dict.exception.DictConnectionException;
import ca.ubc.cs317.dict.model.Database;
import ca.ubc.cs317.dict.model.Definition;
import ca.ubc.cs317.dict.model.MatchingStrategy;
import ca.ubc.cs317.dict.util.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;

/**
 * Created by Jonatan on 2017-09-09.
 */
public class DictionaryConnection {

    private static final int DEFAULT_PORT = 2628;

    private Socket socket;
    private BufferedReader input;
    private PrintWriter output;

    private Map<String, Database> databaseMap = new LinkedHashMap<String, Database>();

    /** Establishes a new connection with a DICT server using an explicit host and port number, and handles initial
     * welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @param port Port number used by the DICT server
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host, int port) throws DictConnectionException {

        try {
            socket = new Socket(host, port);

            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            output = new PrintWriter(socket.getOutputStream(), true);

            Status status = Status.readStatus(input);
            if(status.getStatusCode() != 220) {
                throw new DictConnectionException(status.getDetails());
            }
        } 
        catch (Exception e) {
            throw new DictConnectionException(e);
        }
    }

    /** Establishes a new connection with a DICT server using an explicit host, with the default DICT port number, and
     * handles initial welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host) throws DictConnectionException {
        this(host, DEFAULT_PORT);
    }

    /** Sends the final QUIT message and closes the connection with the server. This function ignores any exception that
     * may happen while sending the message, receiving its reply, or closing the connection.
     *
     */
    public synchronized void close() {

        if(socket.isConnected()) {
            try {
                output.println("QUIT");

                Status.readStatus(input);

                socket.close();
            } 
            catch (Exception e) {
                //ignore exception
            }
        }
    }

    /** Requests and retrieves all definitions for a specific word.
     *
     * @param word The word whose definition is to be retrieved.
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 definitions in the first database that has a definition for the word should be used
     *                 (database '!').
     * @return A collection of Definition objects containing all definitions returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Definition> getDefinitions(String word, Database database) throws DictConnectionException {
        Collection<Definition> set = new ArrayList<>();
        getDatabaseList(); // Ensure the list of databases has been populated
        if (word == null || database == null) {
            return set;
        }
        if(socket.isConnected()) {
            try {
                output.println("DEFINE " + database.getName() + " " + "\""+word+"\"");

                // retrieve status code and number of definitions
                String[] arr = input.readLine().split(" ", 3);
                Integer stat = 0;
                Integer numDef = 0;
                try {
                    stat = Integer.parseInt(arr[0]);
                    numDef = Integer.parseInt(arr[1]);
                } catch (NumberFormatException e) {
                    throw new DictConnectionException("Status code number expected ", e);
                }
                // successful word retrieval with status code 150
                if(stat == 150) {
                    // check how many spaces are in the given word
                    arr = word.split(" ");
                    int wordsInWord = arr.length -1;

                    // parse each definition
                    for (int i = 0; i < numDef; i++) {
                        // check status and database name of definition
                        arr = input.readLine().split(" ", 4+wordsInWord);
                        if (arr.length < 4+wordsInWord) {
                            throw new DictConnectionException("Invalid status line in retrieved definition");
                        }
                        stat = 0;
                        try {
                            stat = Integer.parseInt(arr[0]);
                        } catch (NumberFormatException e) {
                            throw new DictConnectionException("Status code number expected ", e);
                        }
                        if (stat == 151 ) {
                            String databaseName = arr[2+wordsInWord];
                            // parse the individual definition
                            boolean end = false;
                            StringBuilder stringBuilder = new StringBuilder();
                            do {
                                String line = input.readLine();
                                if (!line.equals("")) {
                                    if (line.charAt(0) == '.' && line.length() == 1) {
                                        end = true;
                                    } else {
                                        stringBuilder.append(line);
                                    }
                                } else {
                                    stringBuilder.append(System.getProperty("line.separator"));
                                }
                            } while (!end);
                            Database database1 = databaseMap.get(databaseName);
                            if (database1 == null) {
                                throw new DictConnectionException("Could not get definition of " + word);
                            }
                            Definition definition = new Definition(word,database1);
                            definition.setDefinition(stringBuilder.toString());
                            set.add(definition);
                        } else {
                            throw new DictConnectionException("Could not get definition of " + word);
                        }
                    }
                    // process the last status code line
                    Status status = Status.readStatus(input);
                    if(status.getStatusCode() != 250) {
                        throw new DictConnectionException("Lost connection at end of database retrieval");
                    }
                } else if (stat == 552){
                    return set;
                } else {
                    throw new DictConnectionException("Unexpected Message");
                }
            } catch (Exception e) {
                throw new DictConnectionException("Unexpected Message");
            }
        } else {
            throw new DictConnectionException("Socket not connected");
        }
        return set;
    }

    /** Requests and retrieves a list of matches for a specific word pattern.
     *
     * @param word     The word whose definition is to be retrieved.
     * @param strategy The strategy to be used to retrieve the list of matches (e.g., prefix, exact).
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 matches in the first database that has a match for the word should be used (database '!').
     * @return A set of word matches returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<String> getMatchList(String word, MatchingStrategy strategy, Database database) throws DictConnectionException {
        Set<String> set = new LinkedHashSet<>();
        if (word == null || database == null || strategy == null) {
            return set;
        }
        if(socket.isConnected()) {
        	try {
        		output.println("MATCH " + database.getName() + " " + strategy.getName() + " " + "\""+word+"\"");
                Status status = Status.readStatus(input);
                if(status.getStatusCode() == 152) {
                	int numMatches = Integer.parseInt(status.getDetails().split(" ")[0]);
                	try {
                		for (int i = 0; i < numMatches; i++) {
                			String[] info = DictStringParser.splitAtoms(input.readLine());
                			set.add(info[1]);
                		}
                	}
                	catch (IOException e) {
                        throw new DictConnectionException("unexpected message");
                	}

                    // process the . at end of match list
                    String s = input.readLine();
                	if (!s.equals(".")) {
                        throw new DictConnectionException("unexpected message");
                    }

                	// process the 250 status code at the end of the match list
                    status = Status.readStatus(input);
                    if(status.getStatusCode() != 250) {
                        throw new DictConnectionException("Lost connection at end of database retrieval");
                    }
                } 
                else if (status.getStatusCode() == 552){
                    return set;
                } else {
                    throw new DictConnectionException("Unexpected Message");
                }
        	}
        	catch (Exception e) {
                throw new DictConnectionException("Unexpected Message");
        	}
        }
        else {
        	throw new DictConnectionException("Disconnected");
        }       
        return set;
    }

    /** Requests and retrieves a list of all valid databases used in the server. In addition to returning the list, this
     * method also updates the local databaseMap field, which contains a mapping from database name to Database object,
     * to be used by other methods (e.g., getDefinitionMap) to return a Database object based on the name.
     *
     * @return A collection of Database objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Database> getDatabaseList() throws DictConnectionException {

        if (!databaseMap.isEmpty()) return databaseMap.values();
        if(socket.isConnected()) {
                    try {
                        output.println("SHOW DB");
                        Status status = Status.readStatus(input);
                        if(status.getStatusCode() == 110) {
                            //parse databases from the server
                            boolean end = false;
                            do {
                                try {
                                    String[] arr = input.readLine().split(" ", 2);
                                    if (arr.length < 1) {
                                        throw new DictConnectionException();
                                    } else if (arr[0].equals(".")){
                                        end = true;
                                    } else {
                                        databaseMap.put(arr[0], new Database(arr[0], arr[1]));
                                    }
                                } catch (IOException e) {
                                    throw new DictConnectionException();
                                }
                            } while (!end);
                            // process the last status code line
                            status = Status.readStatus(input);
                            if(status.getStatusCode() != 250) {
                                throw new DictConnectionException("Lost connection at end of database retrieval");
                            }
                        } else if (status.getStatusCode() == 554){
                            return databaseMap.values();
                        } else {
                            throw new DictConnectionException("Unexpected Message");
                        }
                    }
                    catch (Exception e) {
                        throw new DictConnectionException("Unexpected Message");
                    }
        } else {
            throw new DictConnectionException("Socket is not connected");
        }
        return databaseMap.values();
    }

    /** Requests and retrieves a list of all valid matching strategies supported by the server.
     *
     * @return A set of MatchingStrategy objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<MatchingStrategy> getStrategyList() throws DictConnectionException {
        Set<MatchingStrategy> set = new LinkedHashSet<>();
        if (socket.isConnected()) {
        	output.println("SHOW STRAT");
        	try {
        		Status status = Status.readStatus(input);
                if(status.getStatusCode() == 111) {
                	int numStrat = Integer.parseInt(status.getDetails().split(" ")[0]);

                	try {
                		for (int i = 0; i < numStrat; i++) {
                			String[] info = DictStringParser.splitAtoms(input.readLine());
                			set.add(new MatchingStrategy(info[0], info[1]));
            			}			
        			} catch (IOException e) {
            			// ignore
        			}

                    // process the . at end of match list
                    String s = input.readLine();
                    if (!s.equals(".")) {
                        throw new DictConnectionException("unexpected message");
                    }
                    // process the last status code line
                    status = Status.readStatus(input);
                    if(status.getStatusCode() != 250) {
                        throw new DictConnectionException("Lost connection at end of database retrieval");
                    }
                }
                else if (status.getStatusCode() == 555) {
                    return set;
                }
                else {
                	throw new DictConnectionException("Unexpected Message");
                }
        	}
        	catch (Exception e) {
                throw new DictConnectionException("Unexpected Message");
        	}
        }
        else {
        	throw new DictConnectionException("Disconnected");
        }
        return set;
    }

}
