/*
 * Copyright 2015 EMBL-EBI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.ac.embl.ebi.ega.egaapiwrapper;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.UdtChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultExecutorServiceFactory;
import io.netty.util.concurrent.ExecutorServiceFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import uk.ac.ebi.ega.cipher.CipherStream_256;
import uk.ac.embl.ebi.ega.utils.EgaFile;
import uk.ac.embl.ebi.ega.utils.EgaTicket;
import us.monoid.json.JSONArray;
import us.monoid.json.JSONObject;
import us.monoid.web.JSONResource;
import us.monoid.web.Resty;
import static us.monoid.web.Resty.content;
import static us.monoid.web.Resty.data;
import static us.monoid.web.Resty.delete;
import static us.monoid.web.Resty.form;
import uk.ac.embl.ebi.ega.utils.SSLUtilities;
import uk.ac.embl.ebi.ega.utils.MyInputStreamResult;
import uk.ac.embl.ebi.ega.utils.MyResultObject;
import uk.ac.embl.ebi.ega.utils.RestyTimeOutOption;
import us.monoid.json.JSONException;
import us.monoid.web.BinaryResource;

/**
 *
 * @author asenf
 */
public class EgaAPIWrapper {
    
    // Setup Information
    private final String urlPrefix = "/ega/rest/access/v2";
    private final String egaServer = "ega.ebi.ac.uk";
    private String globusPrefix = "/ega/rest/globus/v2";
    private String globusServer = "EGA-globus-server.ebi.ac.uk:8113";
    
    // Connection/Session Information
    private String informationServer = null, dataServer = null, backupDataServer = null;
    private String dest_path;
    private boolean udt = false;
    private boolean ssl = false;
    private boolean verbose = false;
    private String protocol = "";
    
    private volatile boolean session = false;
    private volatile String globusToken = null;
    private volatile String globusUser = null;
    private String globus_message;
    
    // Runtime Information
    private String u;
    private char[] p;
    private String login_message;
    private String sessionId;
    private long lastRestCall;
    
    // Decide between two download options for TCP: 'normal' and 'alternative'
    private boolean alt = false;
    
    public EgaAPIWrapper(String infoServer, String dataServer, boolean ssl,
                         String globusServer, String globusPrefix) {
        this(infoServer, dataServer, ssl);
        this.globusServer = globusServer;
        this.globusPrefix = globusPrefix;
    }
    
    public EgaAPIWrapper(String infoServer, String dataServer, boolean ssl) {

        this.ssl = ssl;
        this.protocol = (this.ssl)?"https://":"http://";
        this.informationServer = infoServer.toLowerCase().trim();
        if (this.informationServer.endsWith("/"))
            this.informationServer = this.informationServer.substring(0, this.informationServer.length()-1);
        this.dataServer = dataServer.toLowerCase().trim();
        if (this.dataServer.endsWith("/"))
            this.dataServer = this.dataServer.substring(0, this.dataServer.length()-1);

        this.dest_path = null;
        this.u = null;
        this.p = null;
        this.login_message = "";
        this.sessionId = null;
        
        this.globus_message = "";

        // Deal with non-CA SSL Certificate
        SSLUtilities.trustAllHostnames();
        SSLUtilities.trustAllHttpsCertificates();
    }
    
    // Constructor with login
    public EgaAPIWrapper(String username, char[] password, 
            String infoServer, String dataServer) {

        this.informationServer = infoServer.toLowerCase().trim();
        if (this.informationServer.endsWith("/"))
            this.informationServer = this.informationServer.substring(0, this.informationServer.length()-1);
        this.dataServer = dataServer.toLowerCase().trim();
        if (this.dataServer.endsWith("/"))
            this.dataServer = this.dataServer.substring(0, this.dataServer.length()-1);
        
        if (!login(username, password))
            throw new IllegalArgumentException("Login incorrect");
    }
    
    public void setBackupDataServer(String dataServer) {
        this.backupDataServer = dataServer;
    }
    
    public void setGlobusServer(String globusserver) {
        this.globusServer = globusserver;
    }
    
    // Returns the IP as it is seen by the service - for IP-related functionality
    private String getPublicIpAddress() {
        String res = null;
        try {
            String localhost = InetAddress.getLocalHost().getHostAddress();
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements()) {
                NetworkInterface ni = (NetworkInterface) e.nextElement();
                if(ni.isLoopback())
                    continue;
                if(ni.isPointToPoint())
                    continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress address = (InetAddress) addresses.nextElement();
                    if(address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        if(!ip.equals(localhost) && this.verbose)
                            System.out.println((res = ip));
                    }
                }
            }
        } catch (Exception e) {
            //e.printStackTrace();
        }
        return res;
    }

    // Functionality is essentially just a frontend to the REST interface
    public boolean login(String username, char[] password) {
        //if (!resume(username)) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(8000, 8000));

                final JSONObject json = new JSONObject();
                json.put("username", URLEncoder.encode(username,"UTF-8"));
                json.put("password", URLEncoder.encode(new String(password),"UTF-8"));

                final String url = this.protocol + this.informationServer + urlPrefix + "/users/login";
                //final String url = "https://ega.ebi.ac.uk/ega/rest/access/v2" + "/users/login";

                JSONResource json1 = restCall(r, url, json, "loginrequest"); // Uses Timout Class
                JSONObject jobj = (JSONObject) json1.get("response");
                JSONArray jsonarr = (JSONArray)jobj.get("result");

                String result = jsonarr.length()>0?jsonarr.getString(0):"";
                this.login_message = result; // Preserve message for later display
                if (!result.toLowerCase().startsWith("success")) {
                    this.session = false;
                    return false;
                } else {
                    this.session = true;
                    this.sessionId = jsonarr.length()>1?jsonarr.getString(1):"";
                }

                this.u = username;
                this.p = new char[password.length];
                System.arraycopy(password, 0, this.p, 0, password.length);

            } catch (Exception ex) {
                this.session = false;
            }
        //}
        
        return this.session;
    }
    
    public String getLoginMessage() {
        return this.login_message;
    }
    
    // Shiro keeps sessions, the timeout is set to 10 min. This function uses a
    // REST call to re-connect to an existing session, for the specified user
    public boolean resume(String user) {
        try {
            final Resty r = new Resty(new RestyTimeOutOption(8000, 4000));
            
            final String url = this.protocol + this.informationServer + urlPrefix + "/users/resume?user=" + URLEncoder.encode(user,"UTF-8");

            JSONResource json1 = restCall(r, url, null, null); // Uses Timout Class
            JSONObject jobj = (JSONObject) json1.get("header");
            int code = jobj.getInt("code");
            if (code == 200) {
                this.login_message = "Session Resumed!";
                this.session = true;
                JSONObject jobj1 = (JSONObject) json1.get("response");
                JSONArray jsonarr = (JSONArray)jobj1.get("result");
                this.sessionId = jsonarr.length()>1?jsonarr.getString(1):null;
            }
        } catch (Exception ex) {
            this.session = false;
        }
        
        return this.session;
    }

    // Actively log out of a Shiro session
    public void logout() {
        logout(false);
    }
    public void logout(boolean verbose) {
        if (this.session) {
            this.login_message = "";
            
            try {
                final Resty r = new Resty(new RestyTimeOutOption(8000, 4000));
                final String url = this.protocol + this.informationServer + urlPrefix + "/users/logout";

                JSONResource json1 = restCall(r, url, null, null);
                JSONObject jobj = (JSONObject) json1.get("response");
                JSONArray jsonarr = (JSONArray)jobj.get("result");

                this.session = false;
                this.sessionId = null;
            } catch (Exception ex) {
                this.session = false;
                this.sessionId = null;
                if (verbose) System.out.println("Exception: " + ex.toString());
            }
        }
    }

    // *************************************************************************
    // *************************************************************************
    // Get a Globus Access Token via Globus-Login
    public boolean globusLogin(String username) {
        return globusLogin(username, null);
    }
    public boolean globusLogin(String username, char[] password) {
        try {
            final Resty r = new Resty(new RestyTimeOutOption(10000, 10000));
            
            /*
             * Logging in is simplified with long-lived tokens: First try to get
             * an existing token - only if this fails, perform a login action to
             * get a token from the Globus Nexus API
             */

            // Step 1:
            final String tokenUrl = "https://" + this.globusServer + this.globusPrefix + "/users/" + URLEncoder.encode(username, "UTF-8") + "/gettoken";
            JSONResource json0 = restCall(r, tokenUrl, null, null);
            String tok = "";
            if (json0 != null) {
                JSONObject jobj0 = (JSONObject) json0.get("response");
                JSONArray jsonarr0 = (JSONArray)jobj0.get("result");
                if (jsonarr0.length()>0 && jsonarr0.getString(0).length() > 0)
                    tok = jsonarr0.getString(0);
            }
            
            // Step 2: If no valid token was returned:
            if (tok.length() == 0) {
                if (password == null) // If no password was specified - exit
                    return false;
                
                String result = authenticateGlobusAPI(username, new String(password));
                
                if (result.equalsIgnoreCase("null")) result = "false";
                if (result.length()==0 || result.equals("false"))
                    return false;
                else
                    this.globusToken = result;

            } else {
                this.globusToken = tok;
            }
            this.globusUser = username;
        } catch (Exception ex) {
            this.globusToken = null;
        }
        return (this.globusToken != null && this.globusToken.length() > 0);
    }
    
    private String authenticateGlobusAPI(String user, String pass) {
        String token = null;
        
        //GoauthClient cli = new GoauthClient(user, pass);
        //cli.setIgnoreCertErrors(true);

        try {
            Resty r = new Resty();
            
            System.out.println("Authenticate with Globus API / Basic Auth:");
            String userpass = user+":"+pass;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(userpass.getBytes());
            r.alwaysSend("Authorization", basicAuth);            
            String query = "https://nexus.api.globusonline.org/goauth/token?grant_type=client_credentials";
            JSONResource json = r.json(query);
            
            token = json.get("access_token").toString();
        } catch (Exception ex) {
            Logger.getLogger(EgaAPIWrapper.class.getName()).log(Level.SEVERE, null, ex);
        }
                
        return token;
    }
    
    public String getGlobusMessage() {
        return this.globus_message;
    }
    
    // REST call to new Globus Service - initiate transfer too specified endpoint
    public String globusStartTransfer(String request, String endpoint) {
        String result = "";

        // Must be logged in to Globus!
        if (this.globusToken == null || this.globusToken.length() == 0)
            return null;
        
        final Resty r = new Resty(new RestyTimeOutOption(10000, 5000));
        try {
            String oauthToken = "Bearer " + this.globusToken; // 'Bearer' is expected
            r.alwaysSend("Authorization", oauthToken);
            
            JSONObject json2 = new JSONObject();
            json2.put("username", URLEncoder.encode(this.u, "UTF-8"));
            json2.put("globus_username", URLEncoder.encode(this.globusUser, "UTF-8"));
            json2.put("request", URLEncoder.encode(request, "UTF-8") );
            json2.put("endpoint", URLEncoder.encode(endpoint, "UTF-8") );
            
            String query2 = "https://" + this.globusServer + "/ega/rest/globus/v2/xfers/schedule/" + URLEncoder.encode(this.u, "UTF-8") + "/" + request;
            JSONResource json21 = restCall(r, query2, json2, "globusrequest");
            JSONObject jobj2 = (JSONObject) json21.get("response");
            JSONArray jsonarr2 = (JSONArray)jobj2.get("result");

            System.out.println("Result Length (always 1): " + jsonarr2.length());            
        } catch (Throwable t) {
            System.out.println(t.toString());
        }
        
        return result;
    }
    // *************************************************************************
    // *************************************************************************
    
    public String getUser() {
        return this.u;
    }

    public boolean session() {
        return this.session;
    }
    
    public void setAlt(boolean value) {
        this.alt = value;
    }
    
    public boolean getAlt() {
        return this.alt;
    }

/*
    public String[] localize(String descriptor) {
        String[] result = null;
        
        try {
            final Resty r = new Resty(new RestyTimeOutOption(8000, 4000));
            
            final String url = this.protocol + this.informationServer + urlPrefix + "/requests/"+descriptor+"/localize";

            JSONResource json = r.json(url, put(content(new JSONObject())));
            JSONObject jobj = (JSONObject) json.get("response");
            JSONArray jsonarr = (JSONArray)jobj.get("result");

            result = new String[jsonarr.length()];
            for (int i=0; i<jsonarr.length(); i++)
                result[i] = jsonarr.getString(i);
            
        } catch (Exception ex) {
        }
        
        return result;
    }
*/    
    // Specify a download location; otherwise the current working directory is used
    public boolean setSetPath(String path) {
        if (path==null || path.length()==0 || path.equals(".")) {
            this.dest_path = null;
            return true;
        } else {
            File testpath = new File(path);
            if (!testpath.exists())
                testpath.mkdirs();
            if (testpath.exists() && testpath.isDirectory()) {
                this.dest_path = path;
                if (this.dest_path!= null && this.dest_path.length() > 0 && !this.dest_path.endsWith("/")) 
                    this.dest_path = this.dest_path + "/";
                if (verbose) System.out.println("Path set to: " + testpath.getAbsolutePath());
                return true;
            }
        }
        this.dest_path = null;
        if (verbose) System.out.println("Could not set path set to: " + path);
        return false;
    }
    
    public String getPath() {
        if (this.dest_path != null && this.dest_path.length()>0)
            return this.dest_path;
        else
            return ".";
    }
    
    public void setVerbose(boolean value) {
        this.verbose = value;
    }    
    public boolean getVerbose() {
        return this.verbose;
    }
    
    // Data transfer - switch bewtween TCP and UDT modes
    public void setUdt(boolean value) {
        this.udt = value;
    }    
    public boolean getUdt() {
        return this.udt;
    }
    
    public String getInfoServer() {
        return this.protocol + this.informationServer;
    }
    
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Wrapper functions for SessionID REST queries: Level 1 Functionality
    // These functions require a session tiken - which is obtained by prior login
    
    // List all datasets
    public String[] listDatasets() { // OK
        String[] result = null;

        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(10000, 5000));

                String url = this.protocol + this.informationServer + urlPrefix + "/datasets";

                JSONArray jsonarr = handleCall(r, url);
                if (jsonarr != null) {
                    result = new String[jsonarr.length()];
                    for (int i=0; i<jsonarr.length(); i++)
                        result[i] = jsonarr.getString(i);
                } else {
                    this.session = false;
                    result = new String[]{"Session timed out."};
                }
            } catch (Exception ex) {
                System.out.println("(TEST) Error: " + ex.getLocalizedMessage());
            }

            if (result!=null)
                Arrays.sort(result);    
        } else
            System.out.println("Please log in.");        
        return result;
    }
    
    // List files in a dataset
    public EgaFile[] listDatasetFiles(String dataset) { // OK
        EgaFile[] result = null;

        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(20000, 10000));

                String url = this.protocol + this.informationServer + urlPrefix + "/datasets/" + dataset + "/files";

                JSONArray jsonarr = handleCall(r, url);
                if (jsonarr != null) {
                    if (jsonarr.length() > 0 && jsonarr.get(0) instanceof String) {
                        System.out.println(jsonarr.getString(0));
                        return result;
                    } else {                    
                        result = new EgaFile[jsonarr.length()];
                        for (int i=0; i<jsonarr.length(); i++) {
                            JSONObject jsonObject2 = jsonarr.getJSONObject(i);
                            EgaFile x = new EgaFile(jsonObject2.getString("fileID"), 
                                                    jsonObject2.getString("fileName"), 
                                                    jsonObject2.getString("fileIndex"), 
                                                    jsonObject2.getString("fileID"), 
                                                    jsonObject2.getLong("fileSize"), 
                                                    jsonObject2.getString("fileMD5"), 
                                                    jsonObject2.getString("fileStatus"));
                            result[i] = x;
                        }
                    }
                } else {
                    System.out.println("REST Call result Null");
                    //this.session = false;
                    result = null;
                }
            } catch (Exception ex) {;}

            //if (result!=null)
            //    Arrays.sort(result);       
        } else
            System.out.println("Please log in.");
        return result;
    }

    // Information about one specific file
    public EgaFile[] listFileInfo(String file) {
        EgaFile[] result = null;
        
        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(20000, 10000));

                String url = this.protocol + this.informationServer + urlPrefix + "/files/" + file;

                JSONArray jsonarr = handleCall(r, url);
                if (jsonarr != null) {
                    if (jsonarr.length() > 0 && jsonarr.get(0) instanceof String) {
                        System.out.println(jsonarr.getString(0));
                        return result;
                    } else {                    
                        result = new EgaFile[jsonarr.length()];
                        for (int i=0; i<jsonarr.length(); i++) {
                            JSONObject jsonObject2 = jsonarr.getJSONObject(i);
                            EgaFile x = new EgaFile(jsonObject2.getString("fileID"), 
                                                    jsonObject2.getString("fileName"), 
                                                    jsonObject2.getString("fileIndex"), 
                                                    jsonObject2.getString("fileID"), 
                                                    jsonObject2.getLong("fileSize"), 
                                                    jsonObject2.getString("fileMD5"), 
                                                    jsonObject2.getString("fileStatus"));
                            result[i] = x;
                        }
                    }
                } else {
                    this.session = false;
                    result = null;
                }

            } catch (Exception ex) {;}
        } else
            System.out.println("Please log in.");
        
        return result;
    }
    
    // List all download requests (result will be 1 ticket per file)
    public EgaTicket[] listRequest(String descriptor) {
        EgaTicket[] result = null;

        if (this.session) {
            try {
                boolean success = false;
                int countdown = 4, readTO = 12000, connectTO = 6000;
                while (!success && countdown-- > 0) {
                    final Resty r = new Resty(new RestyTimeOutOption(readTO, connectTO));

                    String url = this.protocol + this.informationServer + urlPrefix + "/requests/" + descriptor;
    
                    JSONArray jsonarr = handleCall(r, url);
                    if (jsonarr != null) {
                        result = new EgaTicket[jsonarr.length()];
                        for (int i=0; i<jsonarr.length(); i++) {
                            JSONObject jsonObject2 = jsonarr.getJSONObject(i);

                            EgaTicket x = new EgaTicket(jsonObject2.getString("ticket"), 
                                                        jsonObject2.getString("label"), 
                                                        jsonObject2.getString("fileID"), 
                                                        jsonObject2.getString("fileType"), 
                                                        jsonObject2.getString("fileSize"), 
                                                        jsonObject2.getString("fileName"), 
                                                        jsonObject2.getString("encryptionKey"),   // empty
                                                        jsonObject2.getString("transferType"),    // empty
                                                        jsonObject2.getString("transferTarget"),  // empty?
                                                        jsonObject2.getString("user"));
                            result[i] = x;
                        }
                        success = true;
                    } else {
                        readTO = readTO * 2;
                        //this.session = false;
                        result = null;
                    }
                }
            } catch (Exception ex) {;}

            //if (result!=null)
            //    Arrays.sort(result);       
        } else
            System.out.println("Please log in.");
        return result;
    }

    public String[] listAllRequestsLight() {
        String[] result = null;
        
        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(8000, 8000));

                String url = this.protocol + this.informationServer + urlPrefix + "/requests/light";

                JSONArray jsonarr = handleCall(r, url);
                if (jsonarr != null) {
                    result = new String[jsonarr.length()];
                    for (int i=0; i<jsonarr.length(); i++) {
                        result[i] = jsonarr.getString(i);
                    }
                }
            } catch (Exception ex) {;}
        } else
            System.out.println("Please log in.");
        
        return result;
    }
    
    // ...may not be necessary, if I drop the IP information...
    public EgaTicket[] listAllRequests() {
        EgaTicket[] result = null;

        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(40000, 8000));

                String url = this.protocol + this.informationServer + urlPrefix + "/requests";

                JSONArray jsonarr = handleCall(r, url);
                if (jsonarr != null) {
                    result = new EgaTicket[jsonarr.length()];
                    for (int i=0; i<jsonarr.length(); i++) {
                        JSONObject jsonObject2 = jsonarr.getJSONObject(i);

                        EgaTicket x = new EgaTicket(jsonObject2.getString("ticket"), 
                                                    jsonObject2.getString("label"), 
                                                    jsonObject2.getString("fileID"), 
                                                    jsonObject2.getString("fileType"), 
                                                    jsonObject2.getString("fileSize"), 
                                                    jsonObject2.getString("fileName"), 
                                                    jsonObject2.getString("encryptionKey"), // empty
                                                    jsonObject2.getString("transferType"),  // empty
                                                    jsonObject2.getString("transferTarget"), // empty
                                                    jsonObject2.getString("user"));
                        result[i] = x;
                    }            
                } else {
                    this.session = false;
                    result = null;
                }
            } catch (Exception ex) {;}

            //if (result!=null)
            //    Arrays.sort(result);
        } else
            System.out.println("Please log in.");
        return result;
    }

    // For a given ticket, list details
    public EgaTicket[] listTicketDetails(String ticket) {
        EgaTicket[] result = null;

        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(30000, 15000));

                String url = this.protocol + this.informationServer + urlPrefix + "/requests/ticket/" + ticket;

                JSONArray jsonarr = handleCall(r, url);
                if (jsonarr != null) {
                    result = new EgaTicket[jsonarr.length()];
                    for (int i=0; i<jsonarr.length(); i++) {
                        JSONObject jsonObject2 = jsonarr.getJSONObject(i);

                        EgaTicket x = new EgaTicket(jsonObject2.getString("ticket"), 
                                                    jsonObject2.getString("label"), 
                                                    jsonObject2.getString("fileID"), 
                                                    jsonObject2.getString("fileType"), 
                                                    jsonObject2.getString("fileSize"), 
                                                    jsonObject2.getString("fileName"), 
                                                    jsonObject2.getString("encryptionKey"), // empty
                                                    jsonObject2.getString("transferType"),  // empty
                                                    jsonObject2.getString("transferTarget"), // empty
                                                    jsonObject2.getString("user"));
                        result[i] = x;
                    }
                } else {
                    this.session = false;
                    result = null;
                }
            } catch (Exception ex) {;}        
        } else
            System.out.println("Please log in.");
        return result;
    }
    
    // Make a download request - by Dataset, Packet, or individual File
    public String[] requestByID(String id, String type, String reKey, String descriptor) {
        return requestByID(id, type, reKey, descriptor, "");
    }
    public String[] requestByID(String id, String type, String reKey, String descriptor, String target) {
        String[] result = null;

        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(50000, 15000));

                JSONObject json = new JSONObject();
                json.put("rekey", reKey);
                json.put("downloadType", "STREAM");
                json.put("descriptor", descriptor);
                //json.put("target", target);       // Currently: AES-128 by default

                String url = this.protocol + this.informationServer + urlPrefix + "/requests/new/"+type+"s/" + id;

                System.out.println("Requesting....");

                JSONArray jsonarr = handlePostCall(r, url, json, "downloadrequest");
                if (jsonarr != null) {
                    result = new String[jsonarr.length()];
                    for (int i=0; i<jsonarr.length(); i++)
                        result[i] = jsonarr.getString(i);
                }                        
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }

            if (result!=null)
                Arrays.sort(result);       
        } else
            System.out.println("Please log in.");
        return result;
    }
    
    // New functionality - specific to EBI-EGA, where metadata is in a specific FTP box
    // Could be improved...
    public String[] download_metadata(String dataset) {
        String[] result = null;
        final MyResultObject mro = new MyResultObject();
        boolean error = false;

        String org = "";
        final String down_name = dataset + ".tar.gz";
        if (dataset.contains("?")) { // Standardise ticket format
            org = dataset.substring(dataset.indexOf("?") + 1);
            dataset = dataset.substring(0, dataset.indexOf("?"));
        }
        final String the_dataset = dataset; // Ticket by itself, to be passed to DL class
        final String the_org = org; // Org, of one is specified

        // Setting up download resource
        final String dServer = this.dataServer;
        String urlstring = "/ega/rest/download/v2/metadata/" + dataset; // don't send protocol ...
        if (org!=null && org.length()>0) urlstring += "?org=" + org; // Mirroring?
        final String server = (dServer.contains(":"))?dServer.substring(0, dServer.indexOf(":")):dServer;
        final int port = (dServer.contains(":"))?Integer.parseInt(dServer.substring(dServer.indexOf(":")+1)):80;
        File mfile = null;

        Resty r = new Resty(new RestyTimeOutOption(10000,10000));
        int countdown = 6;
        boolean errorCondition = true;
        String url = "http://" + server + ":" + port + urlstring;
        System.out.println("URL " + url);
        while (errorCondition && countdown-- > 0) {
            int wait = 4000;
            try {
                BinaryResource bytes = r.bytes(url);

                if (bytes!=null) {
                    InputStream ins = bytes.stream();
                    try {
                        String path = "";
                        if (this.dest_path!=null && this.dest_path.length()>0)
                            path = this.dest_path;

                        mfile = new File(path + dataset + ".tar.gz");
                        mfile.createNewFile();
                        System.out.println(mfile.exists() + "\t" + mfile.getAbsolutePath());
                        FileOutputStream fos = new FileOutputStream(mfile);

                        byte [] buffer = new byte[256];
                        int bytesRead = 0;
                        try {
                            while((bytesRead = ins.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        } catch (Throwable th) {error = true;}
                        fos.close();
                    } finally {
                        ins.close();
                    }
                    
                    errorCondition = false;
                }

            } catch (IOException ex) {
                Logger.getLogger(EgaAPIWrapper.class.getName()).log(Level.SEVERE, null, ex);
            }
            if (errorCondition) {
                Random x = new Random();
                long y = Math.abs(x.nextInt(wait));
                try {
                    //System.out.println("Wait: " + y + " (" + wait + ") " + url);
                    Thread.sleep( (y>wait?wait:y) );
                } catch (InterruptedException ex) {;}
            }
        }
        
        System.gc(); // Just in case!
        
        if (mfile!=null && !error) {
            String l = String.valueOf(mfile.length());
            result = new String[]{mfile.exists()?"true":"false", mfile.getAbsolutePath()};
        } else {
            result = new String[]{"Error - File is Null. Metadata does not yet exist for this dataset. Or the specified dataset ".concat(dataset).concat(" doesn't exist.")};
            if (mfile!=null) mfile.delete();
        }

        return result;
    }
    
    // Download Command, Using Netty Client: download and verify one ticket download (works for UDT and TCP)
    public String[] download_netty(String ticket, final String down_name, String org) {
        String[] result = null;
        final MyResultObject mro = new MyResultObject();

        if (ticket.contains("?")) { // Standardise ticket format
            org = ticket.substring(ticket.indexOf("?") + 1);
            ticket = ticket.substring(0, ticket.indexOf("?"));
        }
        final String the_ticket = ticket; // Ticket by itself, to be passed to DL class
        final String the_org = org; // Org, of one is specified

        // Setting up download resource
        final String dServer = this.dataServer;
        String urlstring = "/ega/rest/download/v2/downloads/" + ticket; // don't send protocol ...
        if (org!=null && org.length()>0) urlstring += "?org=" + org; // Mirroring?
        final String server = (dServer.contains(":"))?dServer.substring(0, dServer.indexOf(":")):dServer;
        final int port = (dServer.contains(":"))?Integer.parseInt(dServer.substring(dServer.indexOf(":")+1)):80;

        // Prepare the HTTP request.
        if (verbose) System.out.println("uri_string: " + urlstring);
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, urlstring.trim());
        request.headers().set(HttpHeaderNames.HOST, dServer);
        request.headers().set(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_OCTET_STREAM);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        HttpHeaderUtil.setKeepAlive(request, true); // ??
        
        final HttpRequest the_request = request;
        
        // Create a Netty Client with the sole purpose of downloading one stream
        ExecutorServiceFactory connectFactory = new DefaultExecutorServiceFactory("connect");
        NioEventLoopGroup connectGroup = this.udt?
                new NioEventLoopGroup(1, connectFactory, NioUdtProvider.BYTE_PROVIDER):
                new NioEventLoopGroup();        
        try {
            Bootstrap boot = new Bootstrap();
            boot.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000); // Time out after 15 seconds
            if (this.udt) { // UDT Setup
                boot.group(connectGroup)
                    .channelFactory(NioUdtProvider.BYTE_CONNECTOR)
                    .handler(new ChannelInitializer<UdtChannel>() {
                        @Override
                        public void initChannel(final UdtChannel ch)
                                throws Exception {
                            ch.pipeline().addLast(
                                    new HttpClientCodec(),
                                    new EgaSimpleNettyHandler(the_request, down_name, dest_path, the_ticket, mro, server, port, the_org)); // Pass request to handler
                        }
                    });
            } else { // TCP Setup
                boot.group(connectGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch)
                                throws Exception {
                            ch.pipeline().addLast(
                                    new HttpClientCodec(),
                                    new EgaSimpleNettyHandler(the_request, down_name, dest_path, the_ticket, mro, server, port, the_org)); // Pass request to handler
                        }
                    });
            }
            
            // Start the client - connect to the correct server
            ChannelFuture f = boot.connect(server, port).sync();
            
            // Wait until the connection is closed - subject to timeout
            f.channel().closeFuture().sync();            
        } catch (InterruptedException ex) {
            // Error during execution
            System.out.println("Error during Execution: " + ex.getLocalizedMessage());
        } finally {
            // Shut down the event loop to terminate all threads.
            if (verbose) System.out.println("Shutting down " + (this.udt?"UDT":"TCP") + " Channel ticket " + ticket);
            connectGroup.shutdownGracefully();
            while (!connectGroup.isShutdown()) { // Wait
                try {Thread.sleep(250);} catch (InterruptedException ex) {;}
            }
            if (verbose) System.out.println("Shut down " + (this.udt?"UDT":"TCP") + " Channel ticket " + ticket + " Done!");
        }        
        System.gc(); // Just in case!
        
        result = new String[]{String.valueOf(mro.size),(mro.success?"Success":"False"), String.valueOf(mro.skip_md5)};
        return result;
    }
    
    // Download Command, using standard Java HttpURLConnections - preferred for TCP
    public String[] download_tcp_url(String ticket, String down_name, String org) {
        return download_tcp_url(ticket, down_name, org, false);
    }
    public String[] download_tcp_url(String ticket, String down_name, String org, boolean dtn) {
        if (this.udt) return download_netty(ticket, down_name, org); // Hack - UDT required Netty
        
        String[] result = null;
        StringBuilder sb = new StringBuilder();
        sb.append("Ticket: ").append(ticket).append("\n");
        
        int stage = 0;
        File out = null;
        try {
            if (ticket.contains("?")) { // Standardise ticket format
                org = ticket.substring(ticket.indexOf("?") + 1);
                ticket = ticket.substring(0, ticket.indexOf("?"));
            }
            
            // Setting up download resource
            String urlstring = "", dServer = this.dataServer ;
            urlstring = "http://" + dServer + "/ega/rest/ds/v2/downloads/" + ticket;
            if (org!=null && org.length()>0) urlstring += "?org=" + org; // Mirroring?
            sb.append("URL: ").append(urlstring).append("\n");
            
            // Set up incoming MD5 Digest
            MessageDigest md;
            md = MessageDigest.getInstance("MD5");

            // Make connection
            stage = 1; // Create URL
            if (verbose) System.out.println("Establishing Data Stream for " + ticket);
            sb.append("Establishing Data Stream for: ").append(ticket).append("\n");
            URL url = new URL(urlstring);
            
            stage = 2; // Connect to URL
            MyInputStreamResult xres = null;
            try {
                xres = connect(url);    // Try
            } catch (Throwable th) {
                System.out.println("Connection Throwable: " + (th!=null?th.toString():"null"));
                if (xres!=null) {
                    System.out.println("x result: " + xres.responseMessage + "  " + xres.responseCode);
                }
            }
            if (verbose) System.out.println("Connection Stream for " + ticket);
            stage = 21; // Connect to URL
            if (xres.in_ == null) xres = connect(url);  // Re-try
            if (xres.in_ == null) {                     // Re-try at backup
                dServer = this.backupDataServer;
                urlstring = "http://" + dServer + "/ega/rest/ds/v2/downloads/" + ticket;
                if (org!=null && org.length()>0) urlstring += "?org=" + org; // Mirroring?
                xres = connect(url);
            }
            if (xres == null || xres.in_ == null) return new String[]{"Unable to establish download stream"};
            InputStream in = xres.in_; // If input stream is still null, end download (after 3 tries)
            if (in == null) return new String[]{"Could not connect to resource URL for " + ticket};
            DigestInputStream d_in = new DigestInputStream(in, md);
            if (verbose) System.out.println("Data Stream for " + ticket + " is established");
            sb.append("Data Stream for ").append(ticket).append(" is established").append("\n");
            
            stage = 3; // Create local file
            OutputStream os = null;
            out = null;
            if (down_name != null && !down_name.equalsIgnoreCase("null")) { // A file is specified
                String down_path = down_name;
                if (this.dest_path!= null && this.dest_path.length() > 0)
                    down_path = this.dest_path + down_name;
                if (down_path!=null) {
                    out = new File(down_path);
                    if (out.getParentFile()!=null)
                        out.getParentFile().mkdirs();
                    out.createNewFile();
                }
                if (out!=null) os = new FileOutputStream(out.getAbsolutePath() + ".egastream");

                if (os != null && out.exists()) { // File created successfully
                    if (verbose) System.out.println("File Stream established for " + down_path + " (" + ticket + ")");
                } else { // Error creating file - use ticket as file name in local dir
                    String backupPath = ticket + ".cip";
                    out = new File(backupPath);
                    os = new FileOutputStream(backupPath + ".egastream");
                    
                    if (os != null && verbose) System.out.println("File " + down_path + " could not be created. Using " + out.getAbsolutePath() + " instead.");
                    if (os == null) return new String[]{"Could not create local file. Exiting download."};
                }
                sb.append("Path: ").append(out.getAbsolutePath()).append("\n");
                
            } else { // download to Null
                // Nothing to do
                if (verbose) System.out.println("Download to NULL");
                sb.append("Download to NULL").append("\n");
            }
            
            stage = 4; // Data transfer loop            
            int rd = 0;
            if (verbose) System.out.println("Starting transfer loop for " + ticket);
            sb.append("Starting transfer loop for ").append(ticket).append(" (").append(d_in!=null).append(")\n");
            long t = System.currentTimeMillis(), tt = 0;
            byte[] buffer = new byte[128 * 1024];
            while ( (rd = d_in.read(buffer)) > -1 ) {
                if (os!=null) os.write(buffer, 0, rd);
                tt+=rd;
                if (System.currentTimeMillis()-t>5000 && verbose) {
                    System.out.println("ticket " + ticket + " xferred: " + tt);
                    t = System.currentTimeMillis();
                }
            }
            if (verbose) System.out.println("Transfer loop for " + ticket + " completed.");
            
            stage = 5; // Close streams
            if (os!=null) os.close();
            d_in.close();
            in.close();
            if (xres.in_!=null) xres.in_.close();
            if (xres.in_!=null) xres.urlConn.disconnect();
                
            stage = 6; // Get local MD5                
            byte[] digest = md.digest();
            BigInteger bigInt = new BigInteger(1,digest);
            String hashtext = bigInt.toString(16);
            while(hashtext.length() < 32 )
                hashtext = "0"+hashtext;
                
            stage = 7; // Get Server MD5
            if (ticket.contains("?")) ticket = ticket.substring(0, ticket.indexOf("?"));
            if (verbose) System.out.println("Getting Server MD5 for " + ticket);
            String url_ = "http://" + dServer + "/ega/rest/ds/v2/results/" + ticket + "?md5="+hashtext;

            MyInputStreamResult xres_md5 = null;
            long serverLength = -1;
            int countDown = 3;
            boolean success = false;
            while (!success && countDown-- > 0) {
                try {
                    xres_md5 = connect(new URL(url_));    // Try
                } catch (Throwable th) {
                    System.out.println("Connection Throwable: " + (th!=null?th.toString():"null"));
                    if (xres_md5!=null) {
                        System.out.println("x result: " + xres_md5.responseMessage + "  " + xres_md5.responseCode);
                    }
                }

                JSONObject jobj = null;
                JSONArray jsonarr = null;
                if (xres_md5.responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(xres_md5.in_));
                    String line = "";
                    while ( (line = reader.readLine()) != null) {
                        jobj = new JSONObject(line);
                        JSONObject j = jobj.getJSONObject("response");
                        jsonarr = (JSONArray)j.get("result");
                        serverLength = Long.parseLong(jsonarr.getString(1));
                        success = true;
                    }
                    reader.close();
                }
                xres_md5.in_.close();
                xres_md5.urlConn.disconnect();
            }
            
            if (verbose) System.out.println("Received Server MD5 for " + ticket + ": " + serverLength);

            stage = 8; // Basic check: rename file if successful, delete otherwise
            File local = new File(out.getAbsolutePath() + ".egastream");
            out.delete();
            if (local.length() > 0 && local.length() == serverLength) {
                if (verbose) System.out.println("Success! " + ticket);
                if (verbose) System.out.println("Temp " + local.getCanonicalPath());
                if (verbose) System.out.println("Final " + out.getCanonicalPath());
                boolean renameTo = local.renameTo(out);
                result = new String[]{out.getCanonicalPath()};
                if (verbose) System.out.println("saved and verified: " + result[0] + " for ticket " + ticket + " renamed: " + renameTo);
            } else if (local.length() > 0 && serverLength == -1) {
                if (verbose) System.out.println("MD5 check incomplete " + ticket + ". Please verify MD5 manually for: " + local.getAbsolutePath());
            } else {
                if (verbose) System.out.println("Failed " + ticket + ". Waiting, and re-try.");
                local.delete();
            }
        } catch (Throwable ex) {
            System.out.println("Output: " + sb.toString());
            System.out.println("Download Error occurred at Stage " + stage);
            System.out.println("Error: " + ((ex!=null)?ex.getLocalizedMessage():"null"));
            if (out!=null) {
                File out_temp = new File(out.getAbsolutePath() + ".egastream");
                out_temp.delete();
                out.delete();
            }
        }
        
        return result;
    }

    // Helper function - establist a HttpURLConnection, return streams
    private MyInputStreamResult connect(URL url) {
        MyInputStreamResult x = new MyInputStreamResult();
        x.in_ = null;

        try {
            if (verbose) System.out.println("Opening URL Connection");
            x.urlConn = (HttpURLConnection) url.openConnection();//connect
            x.urlConn.setRequestProperty("Accept", "application/json");
            x.urlConn.setConnectTimeout(15000); // Wait 15 seconds for a connection 
            x.urlConn.setReadTimeout(1000 * 60 * 2); // 2 Minute Read Timeout
            
            x.responseCode = x.urlConn.getResponseCode();
            if (verbose) System.out.println("Connection response code: " + x.responseCode);
            x.responseMessage = x.urlConn.getResponseMessage();
            if (x.responseCode==200)
                x.in_ = x.urlConn.getInputStream();
                //x.in_ = new MyBackgroundInputStream(x.urlConn.getInputStream());
            
        } catch (SocketTimeoutException ex) {
            x.responseCode = 998;
            x.responseMessage = ex.getLocalizedMessage();
        } catch (IOException ex) {
            x.responseCode = 999;
            x.responseMessage = ex.getLocalizedMessage();
        }
        
        return x;
    }

    // REST call to delete a specified ticket - done after successful download
    // TODO - Refactor
    public String[] delete_ticket(String request, String ticket) {
        String[] result = null;
        
        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(20000, 10000));

                String server = this.protocol + this.informationServer + urlPrefix + "/";
                String url = server + "requests/delete/" + request + "/" + ticket + "?session=" + this.sessionId;

                // Re-try loop added (same as in restCall)
                boolean errorCondition = true;
                int countdown = 3, throtteling = 3;

                // Retry REST calls up to 10 times before giving up
                while (errorCondition && countdown-- > 0) {
                
                    // Perform REST call
                    JSONResource json1 = null;
                    try {
                        json1 = r.json(url, delete());
                    } catch (Throwable t) {
                        String error = t.getMessage();
                        if (error.contains("[429] Too Many Requests") && throtteling-->0) { // If 429 is returned, allow for more re-tries
                            System.out.println("Server responds 'Too Many Requests'. Waiting a few seconds before re-trying.");
                            this.lastRestCall = System.currentTimeMillis();
                            countdown++; // Don't count throtteling as 'attempt'
                            wait(15000);
                            continue;
                        } 
                    }
                    
                    if (json1 != null) {
                        try {
                            JSONObject jobj = (JSONObject) json1.get("response");
                            JSONArray jsonarr = (JSONArray)jobj.get("result");
                            errorCondition = printErrorStack(json1);

                            result = new String[jsonarr.length()];
                            for (int i=0; i<jsonarr.length(); i++) {
                                result[i] = jsonarr.getString(i);
                            }
                            errorCondition = false;
                        } catch (Throwable th) {
                            System.out.println("Delete REST Error: " + th.getMessage());
                            errorCondition = true;
                        }
                    }

                    // In case of error, wait a bit, up to 4 seconds
                    if (errorCondition) {
                        Random x = new Random();
                        long y = Math.abs(x.nextInt(4000));
                        try {
                            Thread.sleep( (y>4000?4000:y) );
                        } catch (InterruptedException ex) {;}
                    }
                }
            } catch (Exception ex) {
                Logger.getLogger(EgaAPIWrapper.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else
            System.out.println("Please log in.");
        return result;
    }    

    // Delete a whole request, comprised of multiple tickets, in one go
    public String[] delete_request(String request) {
        String[] result = null;
        
        if (this.session) {
            try {
                final Resty r = new Resty(new RestyTimeOutOption(20000, 10000));

                String server = this.protocol + this.informationServer + urlPrefix + "/";
                String url = server + "requests/delete/" + request + "?session=" + this.sessionId;

                // Re-try loop added (same as in restCall)
                boolean errorCondition = true;
                int countdown = 10;

                // Retry REST calls up to 10 times before giving up
                while (errorCondition && countdown-- > 0) {
                    
                    // Perform REST call
                    JSONResource json1 = null;
                    String code = "";
                    try {
                        json1 = r.json(url, delete());

                        if (json1 != null) {
                            try {
                                JSONObject jobjh = (JSONObject) json1.get("header");
                                code = jobjh.getString("code");
                                if (code.equals("200")) {
                                    JSONObject jobj = (JSONObject) json1.get("response");
                                    JSONArray jsonarr = (JSONArray)jobj.get("result");
                                    errorCondition = printErrorStack(json1);

                                    result = new String[jsonarr.length()];
                                    for (int i=0; i<jsonarr.length(); i++) {
                                        result[i] = jsonarr.getString(i);
                                    } 
                                    errorCondition = false;
                                } else if (code.equals("429")) {
                                    System.out.println("Sleeping 8000");
                                    Thread.sleep(8000);
                                }
                            } catch (Throwable th) {
                                System.out.println("Delete REST Error: " + th.getMessage());
                                errorCondition = true;
                            }
                        }
                    } catch (Throwable th) {
                        System.out.println("Error:: " + th.getMessage());
                        if (json1!=null) {
                            JSONObject jobj = (JSONObject) json1.get("header");
                            System.out.println(jobj.toString());
                            code = jobj.getString("code");
                        }
                    }

                    // In case of error, wait a bit, up to 4 seconds
                    if (errorCondition) {
                        Random x = new Random();
                        int wait = code.equals("429")?8000:4000;
                        long y = Math.abs(x.nextInt(wait));
                        try {
//System.out.println("Watiting: " + y + " (" + wait + ")");
                            Thread.sleep( (y>wait?wait:y) );
                        } catch (InterruptedException ex) {;}
                    }
                }
            } catch (Exception ex) {
                Logger.getLogger(EgaAPIWrapper.class.getName()).log(Level.SEVERE, null, ex);
            }
                
        } else
            System.out.println("Please log in.");
        return result;
    }    
    
    // -------------------------------------------------------------------------

    // Get the IP seen for this client by the server (handle NAT, etc)
    public String myIP() {
        String ip = null;
        
        try {
            final Resty r = new Resty(new RestyTimeOutOption(20000, 10000));
            
            String server = this.protocol + this.informationServer + urlPrefix + "/";
            String url = server+"/stats/ip";
            
            JSONArray jsonarr = handleCall(r, url);
            if (jsonarr != null) {
                if (jsonarr.length() > 0) {
                    ip = jsonarr.getString(0);
                    if (ip.endsWith(","))
                        ip = ip.substring(0, ip.indexOf(",")-1);
                }
            }            
        } catch (Exception ex) {;}
        
        return ip;
    }
    
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    
    // Provide the correct file extension for the downloaded file
    private String prepareFilename(String filename) {
        String result = filename;
        
        // Remove encryption extension, if present
        if (result.toLowerCase().endsWith(".gpg"))
            result = result.substring(0, result.length()-4);

        // Add '.cip' if not present
        if (!result.toLowerCase().endsWith(".cip"))
            result = result + ".cip";
        
        return result;
    }
    
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Value-added functionality: decrypt a downloaded AES-128 file
    public void decrypt(String key, String destination, List<String> files, int pwb) {
        decrypt(key, destination, files, pwb, true);
    }    
    public void decrypt(String key, String destination, List<String> files, int pwb, boolean delete) {
        Cipher the_cipher = null;
        
        try {
            String down_path = destination;
            if (this.dest_path!= null && this.dest_path.length() > 0)
                down_path = this.dest_path + destination; // destination =
            File dest_file = new File(down_path);
            destination = dest_file.getCanonicalPath(); // modify passed-in parameter
            if (down_path.endsWith(File.separator)) down_path = down_path.substring(0, down_path.length()-1);
        } catch (Throwable th) {
            return;
        }
        
        the_cipher = null;
        // Decrypt files using specified key
        for (int i=0; i<files.size(); i++) {
            FileInputStream in = null;
            FileOutputStream out = null;
            try {
                String dec_path = files.get(i);
                if (this.dest_path!= null && this.dest_path.length() > 0 && !dec_path.contains(this.dest_path))
                    dec_path = this.dest_path + dec_path;
                in = new FileInputStream(dec_path);
                
                String outname = files.get(i).substring(0, files.get(i).lastIndexOf("."));
                if (this.dest_path!= null && this.dest_path.length() > 0 && !outname.contains(this.dest_path))
                    outname = destination + File.separator + outname;
                out = new FileOutputStream(outname);
                CipherStream_256 one_stream_ = new CipherStream_256(in, out, 2048, (new String(key.getBytes("UTF-8"))).toCharArray(), false, pwb);
                
                one_stream_.start();
                while (one_stream_.isAlive()) {
                    ;
                }
                
                File f = new File(files.get(i));
                if (delete) f.delete();
                
            } catch (IOException ex) {
                ;
            } finally {
                try {
                    in.close();
                    out.close();
                } catch (IOException ex) {
                    ;
                }
            }
        }
    }

    // Helper function: print error response
    private boolean printErrorStack(JSONResource json1) {
        boolean result = false;
        
        try {
            JSONObject jobjheader = (JSONObject) json1.get("header");
            if (jobjheader != null) {
                String errorStack = jobjheader.getString("errorStack");
                
                if (errorStack!=null && errorStack.length() > 0) {
                    System.out.println("Service responds with error message: " + errorStack);
                    result = true;
                }
            }
        } catch (Exception ex) {
            System.out.println("Error: " + ex.getLocalizedMessage());
        }
        
        return result;
    }
    
    // Helper function: perform a REST call, return JSON resopnse object
    // Value added: error handling, automatic re-tries
    // Value added: send session ID for authentiated sessions as parameter
    private JSONResource restCall(Resty r, String url, JSONObject jsn, String formname) {
        JSONResource json = null;
        
        boolean errorCondition = true, unauthorized = false;
        boolean loginError = false;
        boolean timeoutError = false;
        int countdown = 3, throtteling = 3;
        int wait = 4000;
        String code = "";
        String sURL = url; // URL including Session ID

        // Retry REST calls - take throtteling into account
        while (errorCondition && !timeoutError && countdown-- > 0) {
            
            // Session Clustering Support (session may change upon retry) - attach session ID, if provided:
            if (this.sessionId!=null && this.sessionId.length() > 0) {
                if (url.contains("?"))
                    sURL = url+"&session="+this.sessionId;
                else
                    sURL = url+"?session="+this.sessionId;
            }
        
            // --------------------------------------------------------------
            // CALL
            // Call - depending on what data is supplied with or without form
            try {
                if (jsn==null) { // No Form Data supplied
                    json = r.json(sURL);
                } else if (jsn!=null && formname == null) { // Form data supplied, no Form Name
                    // Submit without form name
                } else if (jsn!=null && formname!=null && formname.length()>0) { // Form Data and Form Name provided
                    json = r.json(sURL, form( data(formname, content(jsn)) )); // Uses Timout Class
                } else { // Not enough information provided to make a REST call
                    System.out.println(" -- Unexpected Error!");
                    System.out.println(" -- URL: " + sURL);
                    return json;
                }
                Object get = json.get("header"); // Minimal Error Check: verify there's a header
                code = ((JSONObject)get).getString("code");
                //System.out.println("CODE " + code);
                if (code.equals("991")) { // Server Session Lost - re-login
                    this.logout();
                    this.login(this.u, this.p);
                    continue;
                }
                if (code.equals("401")) { // Server Session Lost - re-login
                    //System.out.println("Access not permitted.");
                    json = new JSONResource();
                    return json;
                }
                
                errorCondition = false; // So far so good
            } catch (Exception ex) {
                String error = ex.getMessage();
                if (error.contains("[429] Too Many Requests") && throtteling-->0) { // If 429 is returned, allow for more re-tries
                    System.out.println("Server responds 'Too Many Requests'. Waiting a few seconds before re-trying.");
                    this.lastRestCall = System.currentTimeMillis();
                    countdown++; // Don't count throtteling as 'attempt'
                    wait(wait*10);
                    continue;
                }
                errorCondition = true;
            }

            // --------------------------------------------------------------
            // RESULT
            // Was the call cuccessful?
            Object get = null;
            Object header = null;
            boolean timeout = false;
            if ( (json!=null || countdown==0) && !errorCondition) { // Try to extract Return/Header
                try {
                    header = json.get("header");
                    if (header != null) {
                        JSONObject jh = (JSONObject)header;
                        code = jh.getString("code");
                        //System.out.println("Response code (0.0): " + code);
                        //System.out.println("              (0.1): " + header.toString());                            
                        //get = json.get("response");
                        //if (get!=null)
                        //    System.out.println("              (0.2): " + ((JSONObject)get).toString());
                        
                        // If we have a header & code: deal with non-200s
                        if (code.equals("991")) { // No Session - just retry
                            timeout = (System.currentTimeMillis() - this.lastRestCall > 600000);
                            if (!timeout && countdown > 0) {
                                wait(wait);
                                continue; // directly restart loop
                            } 
                            else { // If timeout has occurred - log out
                                System.out.println("(restCall) Session Time Out!");
                                this.logout();
                                timeoutError = true;
                            }
                        } else if (!code.equals("200")) { // All Other errors...
                            System.out.println("Error! " + code + "\t" + header.toString());
                        }
                    }
                    loginError = false;
                } catch (Throwable t) { // Error - re-login if this persists (unless timeout)
                    errorCondition = true;
                }
            } else { // If nothing was returned (json==null) - wait and retry
                wait(wait);                
                if (countdown>0) continue; // directly restart loop
            }
            
            // Handle Error Conditions (Throtteling; Server session Issues)
            if (errorCondition && !timeout && countdown==0) { // Looks like 
                System.out.println("Server error. Refreshing Session!");
                this.logout();
                this.login(this.u, this.p);
                errorCondition = true;
            } else if (timeout) { // Timeout condition
                System.out.println("(restCall) Session Time Out!");
                this.logout();
                timeoutError = true;
            } else { // Wohoo! No Error!
                this.lastRestCall = System.currentTimeMillis();
                code = (code.length()==0?"200":code);
                errorCondition = false;
            }
            
            // In case of error, wait a bit, up to 4 seconds; timout - don't retry
            if (errorCondition && !timeoutError) {
                wait(wait);
            } else if (timeoutError) {
                countdown = 0;
            }
                
        }
        
        // retrn the result object; if all else fails, return null
        return json;
    }
    private void wait(int wait) {
        Random x = new Random();
        long y = Math.abs(x.nextInt(wait));
        try {
            Thread.sleep( (y>wait?wait:y) );
        } catch (InterruptedException ex) {;}
    }
    
    // *************************************************************************
    // Helper function for Shiro session-related issues
    private void handleSessionResponse(JSONResource json) {
        try {
            JSONObject jobj_header = null;
            try {jobj_header = (JSONObject) json.get("header");} catch (Throwable th) {;}
            if (jobj_header!= null && jobj_header.has("code") && jobj_header.getString("code").equals("500") &&
                    jobj_header.getString("errorStack").contains("There is no session with id")) {
                this.session = false;
                System.out.println("Please log in again. The previous session has expired.");
            }
        } catch (JSONException ex) {;}
    }
    
    // Alternate REST call + re-try function -- should be consolidated into restCall! (TODO)
    private JSONArray handleCall(Resty r, String url) {
        JSONArray jsonarr = null;
        
        try {
            // Get Server Response
            JSONObject jobj= null;

            // Try 2 times
            boolean success = false;
            int countdown = 2;            
            while (this.session && !success && countdown-- > 0) { // Try a few times
                JSONResource json = restCall(r, url, null, null); // 1st Try (already tries twice)
                
                if (json != null) {
                    try {
                        jobj = (JSONObject) json.get("response");
                    } catch (Throwable th) {
                        jsonarr = new JSONArray(new String[]{"Access not Permitted"});
                        return jsonarr;
                    }

                    handleSessionResponse(json); // Handle removed sessions
                    if (jobj!= null && jobj.has("result")) {
                        jsonarr = (JSONArray)jobj.get("result");                    
                        success = true;

                        // Parse response - set session to false if server sends that response
                        if (jsonarr!=null && jsonarr.length()>0 && jsonarr.getString(0).startsWith("Session with id [")) {
                            this.session = false;
                        }
                    }
                }
                if (!success) Thread.sleep(467);
            }
        } catch (Throwable t) {System.out.println("Error: " + t.getMessage());}
        
        return jsonarr;
    }
    // REST call with POSTing information
    private JSONArray handlePostCall(Resty r, String url, JSONObject json_post, String formname) {
        JSONArray jsonarr = null;
        
        try {
            // Get Server Response
            JSONObject jobj= null;
            boolean success = false, unauthorized = false;
            int countdown = 3;
            
            while (this.session && !success && !unauthorized && countdown-- > 0) { // Try a few times
                JSONResource json = restCall(r, url, json_post, formname); // 1st Try
                
                try {jobj = (JSONObject) json.get("response");} catch (Throwable th) {
                    // No response - check for 401
                    try {
                        JSONObject header = (JSONObject) json.get("header");
                        String code = header.get("code").toString();
                        if (code.equals("401")) {
                            jsonarr = new JSONArray(new String[]{"Access not Permitted"});
                            unauthorized = true;
                            break; // bypass error-retry if unauthorized
                        }
                    } catch (Exception ex) {
                        jsonarr = new JSONArray(new String[]{"Access not Permitted"});
                        unauthorized = true;
                        break; // bypass error-retry if unauthorized
                    }
                    
                }
                
                handleSessionResponse(json); // Handle removed sessions
                if (jobj!= null && jobj.has("result")) {
                    jsonarr = (JSONArray)jobj.get("result");                    

                    success = true;
                }
                if (!success && !unauthorized) Thread.sleep(267);
            }
        } catch (Throwable t) {;}
        
        return jsonarr;
    }
}
