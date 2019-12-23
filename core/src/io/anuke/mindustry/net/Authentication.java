package io.anuke.mindustry.net;

import io.anuke.annotations.Annotations.*;
import io.anuke.arc.Core;
import io.anuke.arc.Net.HttpMethod;
import io.anuke.arc.Net.HttpRequest;
import io.anuke.arc.func.Cons;
import io.anuke.arc.math.RandomXS128;
import io.anuke.arc.util.Log;
import io.anuke.arc.util.NetJavaImpl;
import io.anuke.arc.util.serialization.*;
import io.anuke.arc.util.serialization.JsonValue.ValueType;
import io.anuke.arc.util.serialization.JsonWriter.OutputType;
import io.anuke.mindustry.entities.type.Player;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.net.Packets.KickReason;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static io.anuke.mindustry.Vars.*;

public class Authentication{
    public static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    /** Represents a response from the api */
    public class ApiResponse<T>{
        /** Whether or not the request is done */
        public boolean finished;
        /** Was the request successful? */
        public boolean success;
        /** Error code from api */
        public String errorCode;
        /** Error description from api */
        public String errorDescription;
        /** Locally thrown exception if request was unsuccessful */
        public Throwable exception;
        /** Result of the request */
        public T result;
        /** Callback on success or error */
        public Cons<ApiResponse<T>> callback;

        public void setResult(T result){
            finished = true;
            success = true;
            this.result = result;
            if(callback != null) Core.app.post(() -> callback.get(this));
        }

        public void setError(String code, String description){
            finished = true;
            success = false;
            this.errorCode = code;
            this.errorDescription = description;
            if(callback != null) Core.app.post(() -> callback.get(this));
        }

        public void setError(Throwable ex){
            finished = true;
            success = false;
            exception = ex;
            if(callback != null) Core.app.post(() -> callback.get(this));
        }

        // should only be called once
        public void done(Cons<ApiResponse<T>> cb){
            if(finished){
                cb.get(this);
                return;
            }
            callback = cb;
        }

        public JsonValue tryParseResponse(String data) {
            if(data == null){
                setError(new RuntimeException("Expected the api to produce a response"));
                return null;
            }
            JsonValue parsed;
            try {
                parsed = new JsonReader().parse(data);
            } catch (SerializationException ex) {
                setError(new RuntimeException("Invalid response from api", ex));
                return null;
            }
            if(!parsed.isObject()){
                setError(new RuntimeException("Expected api response to be a json object"));
                return null;
            }
            try {
                String status = parsed.getString("status");
                if(!status.equals("ok")){
                    setError(parsed.getString("error"), parsed.getString("description"));
                    return null;
                }
                return parsed;
            }catch(IllegalArgumentException ex){
                setError(new RuntimeException("Invalid response from api", ex));
                return null;
            }
        }
    }

    public class LoginResponse{
        public String token;
        public String username;
    }

    /** Represents information used when asking user for credentials */
    public class LoginInfo{
        // values provided by server
        public String authServer;
        public boolean showRegister;
        public String loginNotice;

        // values provided by user
        public String username;
        public String password;

        public Runnable successCallback;
    }

    public NetJavaImpl netImpl = new NetJavaImpl();
    public LoginInfo loginInfo;

    @Remote(targets = Loc.server, priority = PacketPriority.high, variants = Variant.one)
    public static void authenticationRequired(String authServer, String serverIdHash){
        // do not allow server to request authentication twice
        if(netClient.authenticationRequested) return;
        netClient.authenticationRequested = true;

        netClient.authenticating = true;
        ui.loadfrag.show("$connecting.auth");
        ui.loadfrag.setButton(() -> {
            ui.loadfrag.hide();
            netClient.disconnectQuietly();
        });

        // callback hell and overall very ugly code
        auth.doConnect(authServer, serverIdHash).done(response -> {
            if(response.success){
                ui.loadfrag.show("$connecting.data");
                ui.loadfrag.setButton(() -> {
                    ui.loadfrag.hide();
                    netClient.disconnectQuietly();
                });
                netClient.authenticating = false;
                Call.sendAuthenticationResponse(auth.getUsernameForServer(authServer), response.result);
                return;
            }
            if(response.errorCode.equals("INVALID_SESSION")){
                auth.tryLogin(authServer, () -> auth.doConnect(authServer, serverIdHash).done(response2 -> {
                    if(response2.success){
                        ui.loadfrag.show("$connecting.data");
                        ui.loadfrag.setButton(() -> {
                            ui.loadfrag.hide();
                            netClient.disconnectQuietly();
                        });
                        netClient.authenticating = false;
                        Call.sendAuthenticationResponse(auth.getUsernameForServer(authServer), response2.result);
                        return;
                    }
                    disconnectAndShowApiError(response);
                }));
            }else{
                // unexpected error
                disconnectAndShowApiError(response);
            }
        });
    }

    @Remote(targets = Loc.client, priority = PacketPriority.high)
    public static void sendAuthenticationResponse(Player player, String username, String token){
        if(player.con == null) return;
        if(!player.con.authenticationRequested) return;
        player.con.authenticationRequested = false;
        if(username == null || token == null){
            player.con.kick(KickReason.authenticationFailed);
            return;
        }

        String address = auth.getVerifyIp() ? player.con.address : null;
        auth.verifyConnect(username, token, address).done(response -> {
            if(response.success) {
                player.con.authenticated = true;
                player.username = username;
                netServer.finalizeConnect(player);
                Log.info("Authentication succeeded for player &lc{0}&lg (username &lc{1}&lg)", player.name, username);
            }else{
                player.con.kick(KickReason.authenticationFailed);
                if(response.exception != null){
                    Log.err("Unexpected error in authentication", response.exception);
                }else{
                    Log.info("Player &lc{0}&lg failed authentication: {1} ({2})", player.name, response.errorCode, response.errorDescription);
                }
            }
        });
    }

    public static void disconnectAndShowApiError(ApiResponse<?> response){
        netClient.disconnectQuietly();
        ui.loadfrag.hide();
        showApiError(response);
    }

    public static void showApiError(ApiResponse<?> response){
        if(response.exception != null){
            Log.err("Unexpected exception while connecting to authentication server", response.exception);
            ui.showException("$login.error", response.exception);
        }
        if(response.errorCode != null){
            Log.err("Unexpected error from authentication server: " + response.errorCode + " (" + response.errorDescription + ")");
            ui.showErrorMessage(Core.bundle.format("login.error") + "\n\n" +
                    response.errorCode + " (" + response.errorDescription + ")");
        }
    }

    public static String sha256(byte[] input){
        MessageDigest digest;
        try{
            digest = MessageDigest.getInstance("SHA-256");
        }catch(NoSuchAlgorithmException ex){
            throw new RuntimeException(ex);
        }
        byte[] hashed = digest.digest(input);
        char[] hex = new char[hashed.length * 2];
        for (int i = 0; i < hashed.length; i++) {
            int val = hashed[i] & 0xff; // convert to unsigned
            hex[i * 2] = HEX_DIGITS[val >>> 4];
            hex[i * 2 + 1] = HEX_DIGITS[val & 0x0f];
        }
        return new String(hex);
    }

    public boolean enabled(){
        return Core.settings.getBool("authentication-enabled", false);
    }

    public void setEnabled(boolean enabled){
        Core.settings.putSave("authentication-enabled", enabled);
    }

    public String getAuthenticationServer(){
        return Core.settings.getString("authentication-server", defaultAuthServer);
    }

    public void setAuthenticationServer(String server){
        Core.settings.putSave("authentication-server", server);
    }

    public boolean getVerifyIp(){
        return Core.settings.getBool("authentication-verify-ip", true);
    }

    public void setVerifyIp(boolean enable){
        Core.settings.put("authentication-verify-ip", enable);
    }

    public String getServerId(){
        String id = Core.settings.getString("authentication-server-id", null);
        if(id != null) return id;

        byte[] bytes = new byte[12];
        new RandomXS128().nextBytes(bytes);
        String result = new String(Base64Coder.encode(bytes));
        Core.settings.put("authentication-server-id", result);
        String hashed = sha256(bytes);
        Core.settings.put("authentication-server-id-hash", hashed);
        Core.settings.save();
        return result;
    }

    public String getServerIdHash(){
        String hash = Core.settings.getString("authentication-server-id-hash", null);
        if(hash != null) return hash;
        String serverId = getServerId();
        // no reason to calculate the hash twice
        hash = Core.settings.getString("authentication-server-id-hash", null);
        if(hash != null) return hash;
        String hashed = sha256(Base64Coder.decode(serverId));
        Core.settings.putSave("authentication-server-id-hash", hashed);
        return hashed;
    }

    public void handleConnect(Player player){
        String authServer = getAuthenticationServer();
        if(authServer == null){
            Log.err("Authentication was enabled but no authentication server was specified!");
            player.con.kick(KickReason.authenticationFailed);
            return;
        }

        player.con.authenticationRequested = true;
        Call.authenticationRequired(player.con, authServer, getServerIdHash());
    }

    public String buildUrl(String base, String endpoint) {
        if(base.endsWith("/")){
            base = base.substring(0, base.length() - 1);
        }
        return base + endpoint;
    }

    public String getUsernameForServer(String authServer){
        return Core.settings.getString("authentication-username-" + authServer);
    }

    public String getSessionForServer(String authServer){
        return Core.settings.getString("authentication-session-" + authServer);
    }

    public ApiResponse<LoginInfo> fetchServerInfo(String authServer){
        HttpRequest request = new HttpRequest();
        request.method(HttpMethod.GET);
        request.url(buildUrl(authServer, "/api/info"));
        ApiResponse<LoginInfo> response = new ApiResponse<>();
        netImpl.http(request, res -> {
            JsonValue obj = response.tryParseResponse(res.getResultAsString());
            if(obj == null) return;
            LoginInfo info = new LoginInfo();
            info.authServer = authServer;
            try{
                info.showRegister = obj.getBoolean("registrationEnabled");
                info.loginNotice = obj.getString("loginNotice", null);
            }catch(IllegalArgumentException ex){
                response.setError(new RuntimeException("Invalid response from api", ex));
                return;
            }
            response.setResult(info);
        }, response::setError);
        return response;
    }

    public ApiResponse<LoginResponse> doLogin(String authServer, String username, String password){
        JsonValue body = new JsonValue(ValueType.object);
        body.addChild("username", new JsonValue(username));
        body.addChild("password", new JsonValue(password));
        HttpRequest request = new HttpRequest();
        request.method(HttpMethod.POST);
        request.url(buildUrl(authServer, "/api/login"));
        request.header("Content-Type", "application/json");
        request.content(body.toJson(OutputType.json));
        ApiResponse<LoginResponse> response = new ApiResponse<>();
        netImpl.http(request, res -> {
            JsonValue obj = response.tryParseResponse(res.getResultAsString());
            if(obj == null) return;
            LoginResponse loginResponse = new LoginResponse();
            try{
                loginResponse.username = obj.getString("username");
                loginResponse.token = obj.getString("token");
            }catch(IllegalArgumentException ex){
                response.setError(new RuntimeException("Invalid response from api"));
                return;
            }
            Core.settings.put("authentication-session-" + authServer, loginResponse.token);
            // case-corrected username
            Core.settings.put("authentication-username-" + authServer, loginResponse.username);
            Core.settings.save();
            response.setResult(loginResponse);
        }, response::setError);
        return response;
    }

    public ApiResponse<String> doConnect(String authServer, String serverHash){
        ApiResponse<String> response = new ApiResponse<>();
        String token = getSessionForServer(authServer);
        if(token == null){
            response.setError("INVALID_SESSION", "session token not found");
            return response;
        }
        JsonValue body = new JsonValue(ValueType.object);
        body.addChild("serverHash", new JsonValue(serverHash));
        HttpRequest request = new HttpRequest();
        request.method(HttpMethod.POST);
        request.url(buildUrl(authServer, "/api/doconnect"));
        request.header("Content-Type", "application/json");
        request.header("Session", token);
        request.content(body.toJson(OutputType.json));
        netImpl.http(request, res -> {
            JsonValue obj = response.tryParseResponse(res.getResultAsString());
            if(obj == null) return;
            String connectToken;
            try{
                connectToken = obj.getString("token");
            }catch(IllegalArgumentException ex){
                response.setError(new RuntimeException("Invalid response from api", ex));
                return;
            }
            response.setResult(connectToken);
        }, response::setError);
        return response;
    }

    public ApiResponse<Boolean> verifyConnect(String username, String token, String ip){
        JsonValue body = new JsonValue(ValueType.object);
        body.addChild("serverId", new JsonValue(getServerId()));
        body.addChild("username", new JsonValue(username));
        body.addChild("token", new JsonValue(token));
        if(ip != null) body.addChild("ip", new JsonValue(ip));
        HttpRequest request = new HttpRequest();
        request.method(HttpMethod.POST);
        request.url(buildUrl(getAuthenticationServer(), "/api/verifyconnect"));
        request.header("Content-Type", "application/json");
        request.content(body.toJson(OutputType.json));
        ApiResponse<Boolean> response = new ApiResponse<>();
        netImpl.http(request, res -> {
            JsonValue obj = response.tryParseResponse(res.getResultAsString());
            if(obj == null) return;
            response.setResult(true);
        }, response::setError);
        return response;
    }

    public void tryLogin(String authServer, Runnable onSuccess){
        Log.info("Fetching authentication server information for {0}", authServer);
        fetchServerInfo(authServer).done(response -> {
            if(response.success){
                loginInfo = response.result;
                loginInfo.successCallback = onSuccess;
                ui.loadfrag.hide();
                ui.login.show();
                return;
            }
            disconnectAndShowApiError(response);
        });
    }
}