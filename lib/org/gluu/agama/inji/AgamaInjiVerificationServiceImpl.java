package org.gluu.agama.inji;

import io.jans.agama.engine.script.LogUtils;
import io.jans.as.common.model.common.User;
import io.jans.as.common.model.session.SessionId;
import io.jans.as.common.service.common.UserService;
import io.jans.as.server.service.SessionIdService;
import io.jans.service.cdi.util.CdiUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of Agama Inji Verification Service for handling verifiable credentials
 * and user authentication through the Inji platform.
 */
public class AgamaInjiVerificationServiceImpl extends AgamaInjiVerificationService {

    // Attribute name constants
    private static final String INUM_ATTR = "inum";
    private static final String UID = "uid";
    private static final String MAIL = "mail";
    private static final String DISPLAY_NAME = "displayName";
    private static final String GIVEN_NAME = "givenName";
    private static final String VERIFIABLE_CREDENTIALS = "verifiableCredentials";
    
    // Instance fields
    private static AgamaInjiVerificationServiceImpl INSTANCE = null;
    
    private String userInfoFromVc;
    private String verifiableCredentialsJson;
    private String injiBackendBaseUrl;
    private String injiWebBaseUrl;
    private String clientId = "agama-app";
    private String callbackUrl = "";
    
    private Map<String, Object> authorizationDetails = new HashMap<>();
    private HashMap<String, Object> flowConfig;
    private HashMap<String, Object> presentationDefinition;
    private HashMap<String, Object> clientMetadata;
    private List<Map<String, Object>> credentialMappings;
    private HashMap<String, String> vcToGluuMapping;

    public AgamaInjiVerificationServiceImpl() {}

    /**
     * Constructor with configuration map.
     * 
     * @param config Configuration map containing Inji service settings
     */
    public AgamaInjiVerificationServiceImpl(HashMap<String, Object> config) {
        if (config == null) {
            LogUtils.log("ERROR: No configuration provided. Service will not function properly.");
            return;
        }
        
        LogUtils.log("Flow config provided is: %", config);
        flowConfig = config;

        // Validate and load required configuration
        if (flowConfig.get("injiVerifyBaseURL") != null) {
            this.injiBackendBaseUrl = flowConfig.get("injiVerifyBaseURL").toString();
        } else {
            LogUtils.log("ERROR: 'injiVerifyBaseURL' is missing in configuration. Please provide this value.");
        }
        
        if (flowConfig.get("injiWebBaseURL") != null) {
            this.injiWebBaseUrl = flowConfig.get("injiWebBaseURL").toString();
        } else {
            LogUtils.log("ERROR: 'injiWebBaseURL' is missing in configuration. Please provide this value.");
        }
        
        if (flowConfig.get("clientId") != null) {
            this.clientId = flowConfig.get("clientId").toString();
        } else {
            LogUtils.log("WARNING: 'clientId' is missing in configuration. Using default: 'agama-app'");
            this.clientId = "agama-app";
        }
        
        if (flowConfig.get("presentationDefinition") != null) {
            this.presentationDefinition = (HashMap<String, Object>) flowConfig.get("presentationDefinition");
        } else {
            LogUtils.log("ERROR: 'presentationDefinition' is missing in configuration. Please provide this value.");
        }
        
        if (flowConfig.get("clientMetadata") != null) {
            this.clientMetadata = (HashMap<String, Object>) flowConfig.get("clientMetadata");
        } else {
            LogUtils.log("ERROR: 'clientMetadata' is missing in configuration. Please provide this value.");
        }
        
        if (flowConfig.get("agamaCallBackUrl") != null) {
            this.callbackUrl = flowConfig.get("agamaCallBackUrl").toString();
        } else {
            LogUtils.log("ERROR: 'agamaCallBackUrl' is missing in configuration. Please provide this value.");
        }
        
        // Load credential mappings list
        if (flowConfig.get("credentialMappings") != null) {
            this.credentialMappings = (List<Map<String, Object>>) flowConfig.get("credentialMappings");
        } else {
            this.credentialMappings = new ArrayList<>();
            LogUtils.log("ERROR: 'credentialMappings' is missing in configuration. Please provide credential mappings.");
        }
        
        // Set default mapping to first credential type in list
        if (!this.credentialMappings.isEmpty()) {
            Map<String, Object> defaultMapping = this.credentialMappings.get(0);
            this.vcToGluuMapping = (HashMap<String, String>) defaultMapping.get("vcToGluuMapping");
            LogUtils.log("Loaded credential mapping for type: %", defaultMapping.get("credentialType"));
        } else {
            this.vcToGluuMapping = new HashMap<>();
            LogUtils.log("WARNING: No credential mappings configured. Credential extraction will not work.");
        }
    }

    /**
     * Gets singleton instance of the service.
     * 
     * @param config Configuration map
     * @return Singleton instance
     */
    public static synchronized AgamaInjiVerificationServiceImpl getInstance(HashMap<String, Object> config) {
        if (INSTANCE == null) {
            INSTANCE = new AgamaInjiVerificationServiceImpl(config);
        }
        return INSTANCE;
    } 

    @Override
    public Map<String, Object> createVpVerificationRequest() {
        Map<String, Object> responseMap = new HashMap<>();

        try {
            Map<String, String> sessionAttrs = getSessionId().getSessionAttributes();
            LogUtils.log(sessionAttrs);
            
            LogUtils.log("Create VP Verification Request...");
            Map<String, Object> requestPayload = new HashMap<>();
            requestPayload.put("clientId", clientId);
            requestPayload.put("presentationDefinition", presentationDefinition);
            
            String jsonPayload = new ObjectMapper().writeValueAsString(requestPayload);
            LogUtils.log("Payload object: %", requestPayload);
            LogUtils.log("Payload JSON: %", jsonPayload);
            
            String endpoint = this.injiBackendBaseUrl + "/v1/verify/vp-request";
            
            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Cache-Control", "no-cache")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                String jsonResponse = response.body();
                LogUtils.log("INJI Verify Backend Response: %", jsonResponse);
                
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(jsonResponse, Map.class);

                if (data == null || !data.containsKey("requestId") || !data.containsKey("transactionId")) {
                    LogUtils.log("ERROR: Missing Data from INJI backend response");
                    responseMap.put("valid", false);
                    responseMap.put("message", "ERROR: Missing Data from INJI Verify backend response");
                    return responseMap;
                }
                
                String transactionId = (String) data.get("transactionId");
                String requestId = (String) data.get("requestId");
                this.authorizationDetails = (Map<String, Object>) data.get("authorizationDetails");
                
                LogUtils.log("Authorization details: %", this.authorizationDetails);
                
                responseMap.put("valid", true);
                responseMap.put("message", "INJI Verify Backend System response is satisfactory");
                responseMap.put("requestId", requestId);
                responseMap.put("transactionId", transactionId);
                return responseMap;
            } else {
                LogUtils.log("ERROR: INJI Verify returned status code: %", response.statusCode());
                responseMap.put("valid", false);
                responseMap.put("message", "ERROR: INJI BACKEND returned status code: " + response.statusCode());
                return responseMap;
            }
        } catch (Exception e) {
            LogUtils.log("ERROR: Exception in createVpVerificationRequest: %", e.getMessage());
            responseMap.put("valid", false);
            responseMap.put("message", e.getMessage());
        }

        return responseMap;
    }


    @Override
    public String buildInjiWebAuthorizationUrl(String requestId, String transactionId) {
        try {
            LogUtils.log("Preparing Inji web Authorization URL...");

            String nonce = this.authorizationDetails.get("nonce").toString();
            String baseUrl = this.injiWebBaseUrl + "/authorize";

            String presentationDefinitionJson = new JSONObject(
                this.authorizationDetails.get("presentationDefinition")).toString();
            String clientMetadataJson = new JSONObject(this.clientMetadata).toString();

            String url = baseUrl +
                    "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                    "&presentation_definition=" + URLEncoder.encode(presentationDefinitionJson, StandardCharsets.UTF_8) +
                    "&nonce=" + URLEncoder.encode(nonce, StandardCharsets.UTF_8) +
                    "&response_uri=" + URLEncoder.encode((String) this.authorizationDetails.get("responseUri"), StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(this.callbackUrl, StandardCharsets.UTF_8) +
                    "&response_type=" + this.authorizationDetails.get("responseType") +
                    "&response_mode=" + this.authorizationDetails.get("responseMode") +
                    "&client_id_scheme=pre-registered" +
                    "&state=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8) +
                    "&client_metadata=" + URLEncoder.encode(clientMetadataJson, StandardCharsets.UTF_8);

            LogUtils.log("URL: %", url);
            return url;
        } catch (Exception e) {
            LogUtils.log("ERROR: Failed to build Inji Web Authorization URL: %", e.getMessage());
            return null;
        }
    }


    @Override
    public Map<String, Object> verifyInjiAppResult(String requestId, String transactionId) {
        Map<String, Object> response = new HashMap<>();

        LogUtils.log("INJI user back to agama...");

        LogUtils.log("Data : requestId : % transactionId : %", requestId, transactionId);

        String requestIdStatus = checkRequestIdStatus(requestId);

        if (!"VP_SUBMITTED".equals(requestIdStatus)) {
            response.put("valid", false);
            response.put("message", "Error: VP REQUEST ID STATUS is " + requestIdStatus);
            return response;
        }

        String transactionIdStatus = checkTransactionIdStatus(transactionId);

        if (!"SUCCESS".equals(transactionIdStatus)) {
            response.put("valid", false);
            response.put("message", "Error: No VP submission found for given transaction ID " + transactionIdStatus);
            return response;
        }

        response.put("valid", true);
        response.put("message", "VP TOKEN Verification successful");
        return response;

    }    
    
    private String checkTransactionIdStatus(String transactionId) {
        try {
            LogUtils.log("Validating VP TRANSACTION ID STATUS for: %", transactionId);
            String apiUrl = this.injiBackendBaseUrl + "/v1/verify/vp-result/" + transactionId;

            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
                    
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);

                if (data != null && data.containsKey("vpResultStatus")) {
                    List<Map<String, Object>> vcResults = (List<Map<String, Object>>) data.get("vcResults");
                    String vc = (String) vcResults.get(0).get("vc");
                    this.userInfoFromVc = vc;
                    LogUtils.log("INJI: VC info -- %", vc);
                    
                    // Store verifiable credentials as JSON
                    this.verifiableCredentialsJson = buildVerifiableCredentialsJson(vcResults);
                    LogUtils.log("Stored verifiable credentials JSON: %", this.verifiableCredentialsJson);
                    
                    return data.get("vpResultStatus").toString();
                } else {
                    return "UNKNOWN";
                }
            } else {
                LogUtils.log("ERROR: INJI VP TOKEN FOR TRANSACTION ID status code: %", response.statusCode());
                return "UNKNOWN";
            }
        } catch (Exception e) {
            LogUtils.log("ERROR: Exception in checkTransactionIdStatus: %", e.getMessage());
            return "UNKNOWN";
        }
    }

    private String checkRequestIdStatus(String requestId) {
        try {
            LogUtils.log("Validating VP REQUEST STATUS for: %", requestId);
            String apiUrl = this.injiBackendBaseUrl + "/v1/verify/vp-request/" + requestId + "/status";
            
            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                LogUtils.log("INJI VERIFY BACKEND RESPONSE FOR REQUEST-ID: %", response.body());
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);

                if (data != null && data.containsKey("status")) {
                    LogUtils.log("VP REQUEST STATUS: %", data.get("status"));
                    return data.get("status").toString();
                } else {
                    return "UNKNOWN";
                }
            } else {
                LogUtils.log("ERROR: VP Request status code: %", response.statusCode());
                return "UNKNOWN";
            }
        } catch (Exception e) {
            LogUtils.log("ERROR: Exception in GET VP Request STATUS: %", e.getMessage());
            return "UNKNOWN";
        }
    }

    private SessionId getSessionId() {
        SessionIdService sis = CdiUtil.bean(SessionIdService.class); 
        return sis.getSessionId(CdiUtil.bean(HttpServletRequest.class));
    }   
    
    @Override
    public Map<String, String> extractUserInfoFromVC() {
        LogUtils.log("INJI: Extract user info from VC: %", userInfoFromVc);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> gluuAttrs = new HashMap<>();

        if (userInfoFromVc == null) {
            LogUtils.log("Error: No user info found from VC");
            return gluuAttrs;
        }

        try {
            Map<String, Object> vcMap = mapper.readValue(userInfoFromVc, Map.class);
            Map<String, Object> credentialSubject = (Map<String, Object>) vcMap.get("credentialSubject");

            if (credentialSubject == null) {
                LogUtils.log("Error: credentialSubject missing in VC");
                return gluuAttrs;
            }

            for (Map.Entry<String, String> entry : vcToGluuMapping.entrySet()) {
                String vcClaimName = entry.getKey();
                String gluuAttrName = entry.getValue();

                if (credentialSubject.containsKey(vcClaimName)) {
                    Object vcValue = credentialSubject.get(vcClaimName);
                    String normalizedValue = extractVcValue(vcValue);

                    if (normalizedValue != null) {
                        gluuAttrs.put(gluuAttrName, normalizedValue);
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.log("Error parsing VC user info: %", e.getMessage());
        }

        return gluuAttrs;
    }

    @Override
    public Map<String, String> checkUserExists(String email, String uidRef) {
        try {
            LogUtils.log("FIND INFO for mail: % or uidRef: %", email, uidRef);
            
            // First, try to find by uidRef if provided
            if(uidRef != null && !uidRef.isEmpty()){
                User user = getUser(INUM_ATTR, uidRef);
                if (user == null) {
                    LogUtils.log("No existing user found with uidRef: %", uidRef);
                    // Fall through to check by email if provided
                } else {
                    LogUtils.log("Found existing user for uidRef: %", uidRef);
                    return buildUserResult(user, uidRef);
                }
            }

            // Try to find by email if provided
            if (email != null && email.contains("@")) {
                User user = getUser(MAIL, email);
                
                if (user == null) {
                    LogUtils.log("No existing user found for email: %", email);
                    return null;
                }

                LogUtils.log("Found existing user for email: %", email);
                String inum = getSingleValuedAttr(user, INUM_ATTR);
                return buildUserResult(user, inum);
            }
            
            LogUtils.log("Error: Neither valid email nor uidRef provided");
            return null;
            
        } catch (Exception e) {
            LogUtils.log("Error in checkUserExists: %", e.getMessage());
            return null;
        }
    }
    
    @Override
    public Map<String, String> checkUserExists(String email) {
        return checkUserExists(email, null);
    }
    
    private Map<String, String> buildUserResult(User user, String inum) {
        try {
            String mail = getSingleValuedAttr(user, MAIL);
            String uid = getSingleValuedAttr(user, UID);
            String displayName = getSingleValuedAttr(user, DISPLAY_NAME);
            String givenName = getSingleValuedAttr(user, GIVEN_NAME);
            
            if (givenName == null) {
                givenName = displayName;
                if (givenName == null && mail != null && mail.contains("@")) {
                    givenName = mail.substring(0, mail.indexOf("@"));
                }
            }
            
            // Handle verifiable credentials update
            if (this.verifiableCredentialsJson != null) {
                String existingCredentials = getSingleValuedAttr(user, VERIFIABLE_CREDENTIALS);
                String mergedCredentials = mergeVerifiableCredentials(existingCredentials, this.verifiableCredentialsJson);
                
                // Update user with merged credentials
                user.setAttribute(VERIFIABLE_CREDENTIALS, mergedCredentials);
                
                try {
                    UserService userService = CdiUtil.bean(UserService.class);
                    userService.updateUser(user);
                    LogUtils.log("Updated verifiable credentials for existing user: %", uid);
                } catch (Exception e) {
                    LogUtils.log("Error updating user credentials: %", e.getMessage());
                }
            }
            
            Map<String, String> result = new HashMap<>();
            result.put(UID, uid);
            result.put(INUM_ATTR, inum);
            result.put(MAIL, mail);
            result.put(DISPLAY_NAME, displayName);
            result.put(GIVEN_NAME, givenName);
            
            return result;
        } catch (Exception e) {
            LogUtils.log("Error building user result: %", e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, String> onboardUser(Map<String, String> userInfo, String password) {
        try {
            LogUtils.log("User registration data: %", userInfo);

            if (userInfo.isEmpty()) {
                LogUtils.log("Error: No user data provided");
                return Collections.emptyMap();
            }

            String email = userInfo.get("mail");
            if (email == null || !email.contains("@")) {
                LogUtils.log("Error: Email missing or invalid");
                return Collections.emptyMap();
            }

            if (password == null || password.isEmpty()) {
                LogUtils.log("Error: Password is required");
                return Collections.emptyMap();
            }

            User newUser = new User();
            String uid = email;
            newUser.setAttribute(UID, uid);
            newUser.setAttribute("userPassword", password);
            
            // Set all attributes from userInfo dynamically
            for (Map.Entry<String, String> entry : userInfo.entrySet()) {
                String attrName = entry.getKey();
                String attrValue = entry.getValue();

                if (UID.equals(attrName) || "password".equals(attrName) || "confirmPassword".equals(attrName)) {
                    continue;
                }
                if (VERIFIABLE_CREDENTIALS.equals(attrName)) {
                    continue;
                }
                
                if ("birthdate".equals(attrName)) {
                    try {
                        LocalDate localDate = LocalDate.parse(attrValue.replace('/', '-'));
                        LocalDateTime localDateTime = localDate.atStartOfDay();
                        newUser.setAttribute(attrName, Timestamp.valueOf(localDateTime));
                    } catch (DateTimeParseException e) {
                        LogUtils.log("Warning: Invalid birthdate format: %", attrValue);
                    }
                } else {
                    newUser.setAttribute(attrName, attrValue);
                }
            }
            
            if (userInfo.get(DISPLAY_NAME) != null) {
                newUser.setAttribute(GIVEN_NAME, userInfo.get(DISPLAY_NAME));
            }
            
            // Store verifiable credentials JSON if available
            if (this.verifiableCredentialsJson != null) {
                newUser.setAttribute(VERIFIABLE_CREDENTIALS, this.verifiableCredentialsJson);
                LogUtils.log("Added verifiable credentials to user profile");
            }
            
            LogUtils.log("Final USER: %", newUser);
            UserService userService = CdiUtil.bean(UserService.class);
            newUser = userService.addUser(newUser, true);

            if (newUser == null) {
                LogUtils.log("Error: Failed to add user");
                return Collections.emptyMap();
            }

            LogUtils.log("New user added");

            String inum = getSingleValuedAttr(newUser, INUM_ATTR);
            String firstName = getSingleValuedAttr(newUser, GIVEN_NAME);

            Map<String, String> result = new HashMap<>(userInfo);
            result.put(UID, uid);
            result.put(INUM_ATTR, inum);
            result.put(GIVEN_NAME, firstName);
            return result;
        } catch (Exception e) {
            LogUtils.log("Error in onboardUser: %", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String extractVcValue(Object vcValue) {
        if (vcValue == null) {
            return null;
        }

        if (vcValue instanceof String) {
            return (String) vcValue;
        }

        if (vcValue instanceof List) {
            List<?> list = (List<?>) vcValue;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                Map<String, Object> obj = (Map<String, Object>) list.get(0);
                Object value = obj.get("value");
                if (value != null) {
                    return value.toString();
                }
            }
        }

        return vcValue.toString();
    }

    private static User getUser(String attributeName, String value) {
        UserService userService = CdiUtil.bean(UserService.class);
        return userService.getUserByAttribute(attributeName, value, true);
    }   

    private String buildVerifiableCredentialsJson(List<Map<String, Object>> vcResults) {
        if (vcResults == null || vcResults.isEmpty()) {
            return null;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> credentialsMap = new HashMap<>();
            
            for (int i = 0; i < vcResults.size(); i++) {
                Map<String, Object> vcItem = vcResults.get(i);
                String vcString = (String) vcItem.get("vc");
                
                if (vcString != null) {
                    // Parse the VC JSON string into a JSON object
                    Map<String, Object> vcObject = mapper.readValue(vcString, Map.class);
                    
                    // Detect credential type by checking credentialSubject
                    String credentialType = detectCredentialType(vcObject);
                    
                    credentialsMap.put(credentialType, vcObject);
                    
                    LogUtils.log("Processed verifiable credential %: type=%", i, credentialType);
                }
            }
            
            // Convert the map to JSON string
            return mapper.writeValueAsString(credentialsMap);
            
        } catch (Exception e) {
            LogUtils.log("Error building verifiable credentials JSON: %", e.getMessage());
            return null;
        }
    }

    private String detectCredentialType(Map<String, Object> vcObject) {
        try {
            Map<String, Object> credentialSubject = (Map<String, Object>) vcObject.get("credentialSubject");
            
            if (credentialSubject != null) {
                // Check if it has UIN field - it's NID
                if (credentialSubject.containsKey("UIN")) {
                    return "NID";
                }
                // Check if it has tax-related fields - it's TAX
                if (credentialSubject.containsKey("taxId") || credentialSubject.containsKey("taxNumber")) {
                    return "TAX";
                }
            }
            
            // Fallback: check credential type in VC
            Object typeObj = vcObject.get("type");
            if (typeObj instanceof List) {
                List<?> types = (List<?>) typeObj;
                for (Object type : types) {
                    String typeStr = type.toString();
                    if (typeStr.contains("NationalID") || typeStr.contains("NID")) {
                        return "NID";
                    }
                    if (typeStr.contains("Tax")) {
                        return "TAX";
                    }
                }
            }
            
        } catch (Exception e) {
            LogUtils.log("Error detecting credential type: %", e.getMessage());
        }
        
        // Default fallback
        return "UNKNOWN_" + System.currentTimeMillis();
    }

    private String mergeVerifiableCredentials(String existingJson, String newJson) {
        if (existingJson == null || existingJson.isEmpty()) {
            return newJson;
        }
        
        if (newJson == null || newJson.isEmpty()) {
            return existingJson;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            
            // Parse existing and new credentials
            Map<String, Object> existingMap = mapper.readValue(existingJson, Map.class);
            Map<String, Object> newMap = mapper.readValue(newJson, Map.class);
            
            // Merge: new credentials override existing ones with same key
            existingMap.putAll(newMap);
            
            LogUtils.log("Merged credentials. Total types: %", existingMap);
            
            return mapper.writeValueAsString(existingMap);
            
        } catch (Exception e) {
            LogUtils.log("Error merging credentials: %. Using new credentials only.", e.getMessage());
            return newJson;
        }
    }

    private String getSingleValuedAttr(User user, String attribute) {
        Object value = null;
        if (attribute.equals(UID)) {
            value = user.getUserId();
        } else {
            value = user.getAttribute(attribute, true, false);
        }
        
        // Handle JSONB columns - convert to string if needed
        if (value != null && VERIFIABLE_CREDENTIALS.equals(attribute)) {
            if (value instanceof String) {
                return (String) value;
            } else {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.writeValueAsString(value);
                } catch (Exception e) {
                    LogUtils.log("Error converting JSONB to string: %", e.getMessage());
                    return value.toString();
                }
            }
        }
        
        return value == null ? null : value.toString();
    }

    public boolean removeCredentialType(String email, String credentialType) {
        if (email == null || !email.contains("@")) {
            LogUtils.log("Error: Invalid email provided");
            return false;
        }

        if (credentialType == null || credentialType.isEmpty()) {
            LogUtils.log("Error: Credential type is required");
            return false;
        }

        try {
            User user = getUser(MAIL, email);
            
            if (user == null) {
                LogUtils.log("Error: User not found for email: %", email);
                return false;
            }

            String existingCredentials = getSingleValuedAttr(user, VERIFIABLE_CREDENTIALS);
            
            if (existingCredentials == null || existingCredentials.isEmpty()) {
                LogUtils.log("No credentials found for user: %", email);
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> credentialsMap = mapper.readValue(existingCredentials, Map.class);
            
            if (!credentialsMap.containsKey(credentialType)) {
                LogUtils.log("Credential type '%' not found for user: %", credentialType, email);
                return false;
            }

            // Remove the specified credential type
            credentialsMap.remove(credentialType);
            LogUtils.log("Removed credential type '%' for user: %", credentialType, email);

            // Update user with remaining credentials
            if (credentialsMap.isEmpty()) {
                // If no credentials left, set to null or empty JSON object
                user.setAttribute(VERIFIABLE_CREDENTIALS, "{}");
                LogUtils.log("No credentials remaining, set to empty object");
            } else {
                String updatedCredentials = mapper.writeValueAsString(credentialsMap);
                user.setAttribute(VERIFIABLE_CREDENTIALS, updatedCredentials);
                LogUtils.log("Updated credentials. Remaining types: %", credentialsMap.keySet());
            }

            UserService userService = CdiUtil.bean(UserService.class);
            userService.updateUser(user);
            
            return true;
            
        } catch (Exception e) {
            LogUtils.log("Error removing credential type: %", e.getMessage());
            return false;
        }
    }
}
