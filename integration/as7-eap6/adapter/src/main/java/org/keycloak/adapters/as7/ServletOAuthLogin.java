package org.keycloak.adapters.as7;

import org.jboss.logging.Logger;
import org.keycloak.RSATokenVerifier;
import org.keycloak.VerificationException;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.ServerRequest;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.IDToken;
import org.keycloak.util.KeycloakUriBuilder;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class ServletOAuthLogin {
    private static final Logger log = Logger.getLogger(ServletOAuthLogin.class);
    protected HttpServletRequest request;
    protected HttpServletResponse response;
    protected boolean codePresent;
    protected KeycloakDeployment deployment;
    protected int redirectPort;
    protected String tokenString;
    protected String idTokenString;
    protected IDToken idToken;
    protected AccessToken token;
    protected String refreshToken;

    public ServletOAuthLogin(KeycloakDeployment deployment, HttpServletRequest request, HttpServletResponse response, int redirectPort) {
        this.request = request;
        this.response = response;
        this.deployment = deployment;
        this.redirectPort = redirectPort;
    }

    public String getTokenString() {
        return tokenString;
    }

    public AccessToken getToken() {
        return token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getIdTokenString() {
        return idTokenString;
    }

    public IDToken getIdToken() {
        return idToken;
    }

    protected String getDefaultCookiePath() {
        String path = request.getContextPath();
        if ("".equals(path) || path == null) path = "/";
        return path;
    }

    protected String getRequestUrl() {
        return request.getRequestURL().toString();
    }

    protected boolean isRequestSecure() {
        return request.isSecure();
    }

    protected void sendError(int code) {
        try {
            response.sendError(code);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void sendRedirect(String url) {
        try {
            log.debugv("Sending redirect to: {0}", url);
            response.sendRedirect(url);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected Cookie getCookie(String cookieName) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(cookieName)) {
                return cookie;
            }
        }
        return null;
    }

    protected String getCookieValue(String cookieName) {
        Cookie cookie = getCookie(cookieName);
        if (cookie == null) return null;
        return cookie.getValue();
    }

    protected String getQueryParamValue(String paramName) {
        String query = request.getQueryString();
        if (query == null) return null;
        String[] params = query.split("&");
        for (String param : params) {
            int eq = param.indexOf('=');
            if (eq == -1) continue;
            String name = param.substring(0, eq);
            if (!name.equals(paramName)) continue;
            return param.substring(eq + 1);
        }
        return null;
    }

    public String getError() {
        return getQueryParamValue("error");
    }

    public String getCode() {
        return getQueryParamValue("code");
    }

    protected void setCookie(String name, String value, String domain, String path, boolean secure) {
        Cookie cookie = new Cookie(name, value);
        if (domain != null) cookie.setDomain(domain);
        if (path != null) cookie.setPath(path);
        if (secure) cookie.setSecure(true);
        response.addCookie(cookie);
    }

    protected String getRedirectUri(String state) {
        String url = getRequestUrl();
        if (!isRequestSecure() && deployment.isSslRequired()) {
            int port = redirectPort;
            if (port < 0) {
                // disabled?
                return null;
            }
            KeycloakUriBuilder secureUrl = KeycloakUriBuilder.fromUri(url).scheme("https").port(-1);
            if (port != 443) secureUrl.port(port);
            url = secureUrl.build().toString();
        }
        KeycloakUriBuilder uriBuilder = deployment.getAuthUrl().clone()
                .queryParam("client_id", deployment.getResourceName())
                .queryParam("redirect_uri", url)
                .queryParam("state", state)
                .queryParam("login", "true");
        if (deployment.getScope() != null) {
            uriBuilder.queryParam("scope", deployment.getScope());
        }
        return uriBuilder.build().toString();
    }

    protected static final AtomicLong counter = new AtomicLong();

    protected String getStateCode() {
        return counter.getAndIncrement() + "/" + UUID.randomUUID().toString();
    }

    public void loginRedirect() {
        String state = getStateCode();
        String redirect = getRedirectUri(state);
        if (redirect == null) {
            sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        setCookie(deployment.getStateCookieName(), state, null, getDefaultCookiePath(), deployment.isSslRequired());
        sendRedirect(redirect);
    }

    public boolean checkStateCookie() {
        Cookie stateCookie = getCookie(deployment.getStateCookieName());

        if (stateCookie == null) {
            sendError(HttpServletResponse.SC_BAD_REQUEST);
            log.warn("No state cookie");
            return false;
        }
        // reset the cookie
        log.debug("** reseting application state cookie");
        Cookie reset = new Cookie(deployment.getStateCookieName(), "");
        reset.setPath(getDefaultCookiePath());
        reset.setMaxAge(0);
        response.addCookie(reset);

        String stateCookieValue = getCookieValue(deployment.getStateCookieName());
        // its ok to call request.getParameter() because this should be a redirect
        String state = request.getParameter("state");
        if (state == null) {
            sendError(HttpServletResponse.SC_BAD_REQUEST);
            log.warn("state parameter was null");
            return false;
        }
        if (!state.equals(stateCookieValue)) {
            sendError(HttpServletResponse.SC_BAD_REQUEST);
            log.warn("state parameter invalid");
            log.warn("cookie: " + stateCookieValue);
            log.warn("queryParam: " + state);
            return false;
        }
        return true;

    }

    /**
     * Start or continue the oauth login process.
     * <p/>
     * if code query parameter is not present, then browser is redirected to authUrl.  The redirect URL will be
     * the URL of the current request.
     * <p/>
     * If code query parameter is present, then an access token is obtained by invoking a secure request to the codeUrl.
     * If the access token is obtained, the browser is again redirected to the current request URL, but any OAuth
     * protocol specific query parameters are removed.
     *
     * @return true if an access token was obtained
     */
    public boolean resolveCode(String code) {
        // abort if not HTTPS
        if (deployment.isSslRequired() && !isRequestSecure()) {
            log.error("SSL is required");
            sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        if (!checkStateCookie()) return false;

        String redirectUri = stripOauthParametersFromRedirect();
        AccessTokenResponse tokenResponse = null;
        try {
            tokenResponse = ServerRequest.invokeAccessCodeToToken(deployment, code, redirectUri);
        } catch (ServerRequest.HttpFailure failure) {
            log.error("failed to turn code into token");
            log.error("status from server: " + failure.getStatus());
            if (failure.getStatus() == HttpServletResponse.SC_BAD_REQUEST && failure.getError() != null) {
                log.error("   " + failure.getError());
            }
            sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;

        } catch (IOException e) {
            log.error("failed to turn code into token");
            sendError(HttpServletResponse.SC_FORBIDDEN);
        }

        tokenString = tokenResponse.getToken();
        idTokenString = tokenResponse.getIdToken();
        try {
            token = RSATokenVerifier.verifyToken(tokenString, deployment.getRealmKey(), deployment.getRealm());
            if (idTokenString != null) {
                JWSInput input = new JWSInput(idTokenString);
                try {
                    idToken = input.readJsonContent(IDToken.class);
                } catch (IOException e) {
                    throw new VerificationException();
                }
            }
            log.debug("Token Verification succeeded!");
        } catch (VerificationException e) {
            log.error("failed verification of token");
            sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        if (tokenResponse.getNotBeforePolicy() > deployment.getNotBefore()) {
            deployment.setNotBefore(tokenResponse.getNotBeforePolicy());
        }
        if (token.getIssuedAt() < deployment.getNotBefore()) {
            log.error("Stale token");
            sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        refreshToken = tokenResponse.getRefreshToken();
        // redirect to URL without oauth query parameters
        sendRedirect(redirectUri);
        return true;
    }

    /**
     * strip out unwanted query parameters and redirect so bookmarks don't retain oauth protocol bits
     */
    protected String stripOauthParametersFromRedirect() {
        StringBuffer buf = request.getRequestURL().append("?").append(request.getQueryString());
        KeycloakUriBuilder builder = KeycloakUriBuilder.fromUri(buf.toString())
                .replaceQueryParam("code", null)
                .replaceQueryParam("state", null);
        return builder.build().toString();
    }


}
