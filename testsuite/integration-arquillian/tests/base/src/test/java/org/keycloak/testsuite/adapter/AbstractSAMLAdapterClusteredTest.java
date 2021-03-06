/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.adapter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.keycloak.testsuite.admin.Users.setPasswordFor;
import static org.keycloak.testsuite.arquillian.DeploymentTargetModifier.APP_SERVER_CURRENT;
import static org.keycloak.testsuite.auth.page.AuthRealm.DEMO;
import static org.keycloak.testsuite.utils.io.IOUtil.loadRealm;
import static org.keycloak.testsuite.util.URLAssert.assertCurrentUrlStartsWith;

import io.undertow.Undertow;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.BiConsumer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.client.methods.HttpGet;
import org.jboss.arquillian.container.test.api.*;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.logging.Logger;
import org.junit.*;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.*;
import org.keycloak.common.util.Retry;
import org.keycloak.testsuite.adapter.page.EmployeeServletDistributable;
import org.keycloak.testsuite.adapter.page.SAMLServlet;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.arquillian.AppServerTestEnricher;
import org.keycloak.testsuite.arquillian.ContainerInfo;
import org.keycloak.testsuite.auth.page.AuthRealm;
import org.keycloak.testsuite.auth.page.login.*;
import org.keycloak.testsuite.page.AbstractPage;
import org.keycloak.testsuite.util.Matchers;
import org.keycloak.testsuite.util.SamlClient;
import org.keycloak.testsuite.util.SamlClient.Binding;
import org.keycloak.testsuite.util.SamlClientBuilder;
import org.keycloak.testsuite.util.WaitUtils;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 *
 * @author hmlnarik
 */
public abstract class AbstractSAMLAdapterClusteredTest extends AbstractServletsAdapterTest {

    protected static final String NODE_1_NAME = "ha-node-1";
    protected static final String NODE_2_NAME = "ha-node-2";

    // target containers will be replaced in runtime in DeploymentTargetModifier by real app-server
    public static final String TARGET_CONTAINER_NODE_1 = APP_SERVER_CURRENT + NODE_1_NAME;
    public static final String TARGET_CONTAINER_NODE_2 = APP_SERVER_CURRENT + NODE_2_NAME;

    protected static final int PORT_OFFSET_NODE_REVPROXY = NumberUtils.toInt(System.getProperty("app.server.reverse-proxy.port.offset"), -1);
    protected static final int HTTP_PORT_NODE_REVPROXY = 8080 + PORT_OFFSET_NODE_REVPROXY;
    protected static final int PORT_OFFSET_NODE_1 = NumberUtils.toInt(System.getProperty("app.server.1.port.offset"), -1);
    protected static final int HTTP_PORT_NODE_1 = 8080 + PORT_OFFSET_NODE_1;
    protected static final int PORT_OFFSET_NODE_2 = NumberUtils.toInt(System.getProperty("app.server.2.port.offset"), -1);
    protected static final int HTTP_PORT_NODE_2 = 8080 + PORT_OFFSET_NODE_2;
    protected static final URI NODE_1_URI = URI.create("http://localhost:" + HTTP_PORT_NODE_1);
    protected static final URI NODE_2_URI = URI.create("http://localhost:" + HTTP_PORT_NODE_2);

    protected LoadBalancingProxyClient loadBalancerToNodes;
    protected Undertow reverseProxyToNodes;

    @ArquillianResource
    protected ContainerController controller;

    @ArquillianResource
    protected Deployer deployer;

    @Page
    LoginActions loginActionsPage;

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        testRealms.add(loadRealm("/adapter-test/keycloak-saml/testsaml-behind-lb.json"));
    }

    @Override
    public void setDefaultPageUriParameters() {
        super.setDefaultPageUriParameters();

        testRealmSAMLPostLoginPage.setAuthRealm(DEMO);
        loginPage.setAuthRealm(DEMO);
        loginActionsPage.setAuthRealm(DEMO);
    }

    @BeforeClass
    public static void checkPropertiesSet() {
        Assume.assumeThat(PORT_OFFSET_NODE_1, not(is(-1)));
        Assume.assumeThat(PORT_OFFSET_NODE_2, not(is(-1)));
        Assume.assumeThat(PORT_OFFSET_NODE_REVPROXY, not(is(-1)));
        assumeNotElytronAdapter();
    }

    @Before
    public void prepareReverseProxy() throws Exception {
        loadBalancerToNodes = new LoadBalancingProxyClient().addHost(NODE_1_URI, NODE_1_NAME).setConnectionsPerThread(10);
        reverseProxyToNodes = Undertow.builder().addHttpListener(HTTP_PORT_NODE_REVPROXY, "localhost").setIoThreads(2).setHandler(new ProxyHandler(loadBalancerToNodes, 5000, ResponseCodeHandler.HANDLE_404)).build();
        reverseProxyToNodes.start();
    }

    @Before
    public void startServers() throws Exception {
        prepareServerDirectories();
        
        for (ContainerInfo containerInfo : testContext.getAppServerBackendsInfo()) {
            controller.start(containerInfo.getQualifier());
        }
        deployer.deploy(EmployeeServletDistributable.DEPLOYMENT_NAME);
        deployer.deploy(EmployeeServletDistributable.DEPLOYMENT_NAME + "_2");
    }

    protected abstract void prepareServerDirectories() throws Exception;

    protected void prepareServerDirectory(String baseDir, String targetSubdirectory) throws IOException {
        Path path = Paths.get(System.getProperty("app.server.home"), targetSubdirectory);
        File targetSubdirFile = path.toFile();
        FileUtils.deleteDirectory(targetSubdirFile);
        FileUtils.forceMkdir(targetSubdirFile);
        //workaround for WFARQ-44
        FileUtils.copyDirectory(Paths.get(System.getProperty("app.server.home"), baseDir, "deployments").toFile(), new File(targetSubdirFile, "deployments"));
        FileUtils.copyDirectory(Paths.get(System.getProperty("app.server.home"), baseDir, "configuration").toFile(), new File(targetSubdirFile, "configuration"));
    }

    @After
    public void stopReverseProxy() {
        reverseProxyToNodes.stop();
    }

    @After
    public void stopServers() {
        deployer.undeploy(EmployeeServletDistributable.DEPLOYMENT_NAME);
        deployer.undeploy(EmployeeServletDistributable.DEPLOYMENT_NAME + "_2");

        for (ContainerInfo containerInfo : testContext.getAppServerBackendsInfo()) {
            controller.stop(containerInfo.getQualifier());
        }
    }

    private void testLogoutViaSessionIndex(URL employeeUrl, boolean forceRefreshAtOtherNode, BiConsumer<SamlClientBuilder, String> logoutFunction) {
        setPasswordFor(bburkeUser, CredentialRepresentation.PASSWORD);

        final String employeeUrlString;
        try {
            URL employeeUrlAtRevProxy = new URL(employeeUrl.getProtocol(), employeeUrl.getHost(), HTTP_PORT_NODE_REVPROXY, employeeUrl.getFile());
            employeeUrlString = employeeUrlAtRevProxy.toString();
        } catch (MalformedURLException ex) {
            throw new RuntimeException(ex);
        }

        SamlClientBuilder builder = new SamlClientBuilder()
          // Go to employee URL at reverse proxy which is set to forward to first node
          .navigateTo(employeeUrlString)

          // process redirection to login page
          .processSamlResponse(Binding.POST).build()
          .login().user(bburkeUser).build()
          .processSamlResponse(Binding.POST).build()

          // Returned to the page
          .assertResponse(Matchers.bodyHC(containsString("principal=bburke")))

          // Update the proxy to forward to the second node.
          .addStep(() -> updateProxy(NODE_2_NAME, NODE_2_URI, NODE_1_URI));

        if (forceRefreshAtOtherNode) {
            // Go to employee URL at reverse proxy which is set to forward to _second_ node now
            builder
              .navigateTo(employeeUrlString)
              .doNotFollowRedirects()
              .assertResponse(Matchers.bodyHC(containsString("principal=bburke")));
        }

        // Logout at the _second_ node
        logoutFunction.accept(builder, employeeUrlString);

        SamlClient samlClient = builder.execute();
        delayedCheckLoggedOut(samlClient, employeeUrlString);

        // Update the proxy to forward to the first node.
        updateProxy(NODE_1_NAME, NODE_1_URI, NODE_2_URI);
        delayedCheckLoggedOut(samlClient, employeeUrlString);
    }

    private void delayedCheckLoggedOut(SamlClient samlClient, String url) {
        Retry.execute(() -> {
          samlClient.execute(
            (client, currentURI, currentResponse, context) -> new HttpGet(url),
            (client, currentURI, currentResponse, context) -> {
              assertThat(currentResponse, Matchers.bodyHC(not(containsString("principal=bburke"))));
              return null;
            }
          );
        }, 10, 300);
    }

    private void logoutViaAdminConsole() {
        RealmResource demoRealm = adminClient.realm(DEMO);
        String bburkeId = ApiUtil.findUserByUsername(demoRealm, "bburke").getId();
        demoRealm.users().get(bburkeId).logout();
        log.infov("Logged out via admin console");
    }
    
    private static void assumeNotElytronAdapter() {
        if (!AppServerTestEnricher.isUndertowAppServer()) {
            try {
                boolean contains = FileUtils.readFileToString(Paths.get(System.getProperty("app.server.home"), "standalone", "configuration", "standalone.xml").toFile(), "UTF-8").contains("<security-domain name=\"KeycloakDomain\"");
                if (contains) {
                    Logger.getLogger(AbstractSAMLAdapterClusteredTest.class).debug("Elytron adapter installed: skipping");
                }
                Assume.assumeFalse(contains);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testAdminInitiatedBackchannelLogout(@ArquillianResource
      @OperateOnDeployment(value = EmployeeServletDistributable.DEPLOYMENT_NAME) URL employeeUrl) throws Exception {
        testLogoutViaSessionIndex(employeeUrl, false, (builder, url) -> builder.addStep(this::logoutViaAdminConsole));
    }

    @Test
    public void testAdminInitiatedBackchannelLogoutWithAssertionOfLoggedIn(@ArquillianResource
      @OperateOnDeployment(value = EmployeeServletDistributable.DEPLOYMENT_NAME) URL employeeUrl) throws Exception {
        testLogoutViaSessionIndex(employeeUrl, true, (builder, url) -> builder.addStep(this::logoutViaAdminConsole));
    }

    @Test
    public void testUserInitiatedFrontchannelLogout(@ArquillianResource
      @OperateOnDeployment(value = EmployeeServletDistributable.DEPLOYMENT_NAME) URL employeeUrl) throws Exception {
        testLogoutViaSessionIndex(employeeUrl, false, (builder, url) -> {
            builder
              .navigateTo(url + "?GLO=true")
              .processSamlResponse(Binding.POST).build()    // logout request
              .processSamlResponse(Binding.POST).build()    // logout response
            ;
        });
    }

    @Test
    public void testUserInitiatedFrontchannelLogoutWithAssertionOfLoggedIn(@ArquillianResource
      @OperateOnDeployment(value = EmployeeServletDistributable.DEPLOYMENT_NAME) URL employeeUrl) throws Exception {
        testLogoutViaSessionIndex(employeeUrl, true, (builder, url) -> {
            builder
              .navigateTo(url + "?GLO=true")
              .processSamlResponse(Binding.POST).build()    // logout request
              .processSamlResponse(Binding.POST).build()    // logout response
            ;
        });
    }

    protected void updateProxy(String hostToPointToName, URI hostToPointToUri, URI hostToRemove) {
        loadBalancerToNodes.removeHost(hostToRemove);
        loadBalancerToNodes.addHost(hostToPointToUri, hostToPointToName);
        log.infov("Reverse proxy will direct requests to {0}", hostToPointToUri);
    }

    protected void assertSuccessfulLogin(SAMLServlet page, UserRepresentation user, Login loginPage, String expectedString) {
        page.navigateTo();
        assertCurrentUrlStartsWith(loginPage);
        loginPage.form().login(user);
        WebDriverWait wait = new WebDriverWait(driver, WaitUtils.PAGELOAD_TIMEOUT_MILLIS / 1000);
        wait.until((WebDriver d) -> d.getPageSource().contains(expectedString));
    }

    protected void delayedCheckLoggedOut(AbstractPage page, AuthRealm loginPage) {
        Retry.execute(() -> {
            try {
                checkLoggedOut(page, loginPage);
            } catch (AssertionError | TimeoutException ex) {
                driver.navigate().refresh();
                log.debug("[Retriable] Timed out waiting for login page");
                throw new RuntimeException(ex);
            }
        }, 10, 100);
    }

    protected void checkLoggedOut(AbstractPage page, AuthRealm loginPage) {
        page.navigateTo();
        WaitUtils.waitForPageToLoad();
        assertCurrentUrlStartsWith(loginPage);
    }
} 
