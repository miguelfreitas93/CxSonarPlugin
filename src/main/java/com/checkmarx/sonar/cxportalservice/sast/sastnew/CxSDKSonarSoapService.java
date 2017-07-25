package com.checkmarx.sonar.cxportalservice.sast.sastnew;

import com.checkmarx.soap.client.Credentials;
import com.checkmarx.soap.client.CxSDKWebServiceLocator;
import com.checkmarx.soap.client.CxSDKWebServiceSoap_PortType;
import com.checkmarx.soap.client.CxWSResponseLoginData;
import com.checkmarx.sonar.cxportalservice.sast.CxSSLUtility;
import com.checkmarx.sonar.cxportalservice.sast.exception.ConnectionException;
import com.checkmarx.sonar.dto.CxFullCredentials;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import sun.net.www.protocol.http.HttpURLConnection;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;


/**
 * Created by: zoharby.
 * Date: 20/04/2017.
 */
abstract class CxSDKSonarSoapService {

    private static final String SONAR_WS_WEB_ADDRESS =  "%s/cxwebinterface/sdk/CxSDKWebService.asmx";
    private final static int LCID = 1033; // English

    CxSDKWebServiceSoap_PortType webServiceSoap;


    private volatile String currServerUrl;

    protected Logger logger = Loggers.get(CxSDKSonarSoapService.class);

    public CxSDKSonarSoapService() {
    }

    public String login(CxFullCredentials cxFullCredentials) throws ConnectionException {

        if( webServiceSoap == null || !cxFullCredentials.getCxServerUrl().equals(currServerUrl) ){
            currServerUrl = cxFullCredentials.getCxServerUrl();
            connect(currServerUrl);
            logger.info("Connected to server");
        }

        String sessionId = authenticate(cxFullCredentials);
        logger.info("Login successful");

        return sessionId;
    }


    private void connect(String serverUrl) throws ConnectionException {
        CxSSLUtility.disableSSLCertificateVerification();
        logger.info("Validating server url");
        validateServerUrl(serverUrl);

        try {
            logger.info("Locating Checkmarx web service");
            CxSDKWebServiceLocator cxSdkWebServiceLocator = new CxSDKWebServiceLocator();
            cxSdkWebServiceLocator.setCxSDKWebServiceSoap12EndpointAddress(String.format(SONAR_WS_WEB_ADDRESS, serverUrl));
            cxSdkWebServiceLocator.setCxSDKWebServiceSoapEndpointAddress(String.format(SONAR_WS_WEB_ADDRESS, serverUrl));
            webServiceSoap = cxSdkWebServiceLocator.getCxSDKWebServiceSoap();
        } catch (Exception e) {
            throw logErrorAndCreateConnectionException("Locating Checkmarx web service failed: "+ e.getLocalizedMessage(), e);
        }

        if(webServiceSoap == null){
            throw logErrorAndCreateConnectionException("Could not reach Checkmarx web service.");
        }
    }



    //TODO UNITE THE REDANDENT PARST WITH OLS SOAP SERVICE\ MAKE ONESOPA SERVICE (EXPOSING SDK FOR CONFIGURATION, LESS SECURED)
    private String authenticate(CxFullCredentials cxFullCredentials) throws ConnectionException {
        logger.info("Authenticating Checkmarx client");
        String sessionId = null;

        Credentials credentials = new Credentials();
        credentials.setUser(cxFullCredentials.getCxUsername());
        credentials.setPass(cxFullCredentials.getCxPassword());
        CxWSResponseLoginData cxWSResponseLoginData;
        try {
            cxWSResponseLoginData = webServiceSoap.login(credentials, LCID);
            if (!cxWSResponseLoginData.isIsSuccesfull()) {
                throw logErrorAndCreateConnectionException("Checkmarx server login error: " + cxWSResponseLoginData.getErrorMessage());
            }
            sessionId = cxWSResponseLoginData.getSessionId();
        } catch (javax.xml.ws.WebServiceException | RemoteException e) {
            throw logErrorAndCreateConnectionException("error while retrieving session id: "+e.getLocalizedMessage(), e);
        }

        if(sessionId == null){
            throw logErrorAndCreateConnectionException("Web service did not return session id");
        }
        return sessionId;
    }


    private void validateServerUrl(String serverUrl) throws ConnectionException {
        URL url = getUrlAndValidateForm(serverUrl);
        validateLiveServer(url);
    }

    private URL getUrlAndValidateForm(String serverUrl) throws ConnectionException {
        try{
            URL url = new URL(serverUrl);
            if (url.getPath().length() > 1) {
                throw logErrorAndCreateConnectionException("Checkmarx server url must not contain path: " + serverUrl);
            }
            return url;
        }catch (MalformedURLException e) {
            if (e.getLocalizedMessage().startsWith("no protocol:")) {
                throw logErrorAndCreateConnectionException(e.getLocalizedMessage() + ". Server URL syntax is: http(s)://servername(:port)", e);
            } else {
                throw logErrorAndCreateConnectionException(e.getLocalizedMessage(),e);
            }
        }
    }

    private void validateLiveServer(URL url) throws ConnectionException {
        HttpURLConnection huc = null;
        try {
            huc = (HttpURLConnection) url.openConnection();
            //HEAD minimize loaded data in response
            huc.setRequestMethod("HEAD");
            huc.setConnectTimeout(7000);
            int responseCode = huc.getResponseCode();
            if (responseCode != 200) {
                throw logErrorAndCreateConnectionException("Could not connect to Server URL, response code: "+responseCode);
            }
        } catch (IOException e) {
            throw logErrorAndCreateConnectionException("Could not access Server URL: "+e.getLocalizedMessage(), e);
        }
    }

    public void closeConnection(String sessionId) throws ConnectionException {
        if(webServiceSoap != null && sessionId != null) {
            try {
                webServiceSoap.logout(sessionId);
            } catch (javax.xml.ws.WebServiceException | RemoteException e) {
                throw logErrorAndCreateConnectionException("Could not close connection: "+e.getLocalizedMessage(), e);
            }
        }
        webServiceSoap = null;
    }

    protected ConnectionException logErrorAndCreateConnectionException(String message, Exception e){
        logger.error(message);
        return new ConnectionException(message, e);
    }

    protected ConnectionException logErrorAndCreateConnectionException(String message){
        logger.error(message);
        return new ConnectionException(message);
    }

}