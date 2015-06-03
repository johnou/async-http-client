/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient;

import static java.nio.charset.StandardCharsets.*;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addBasicAuthHandler;
import static org.asynchttpclient.test.TestUtils.addDigestAuthHandler;
import static org.asynchttpclient.test.TestUtils.findFreePort;
import static org.asynchttpclient.test.TestUtils.newJettyHttpServer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.simple.SimpleAsyncHttpClient;
import org.asynchttpclient.simple.consumer.AppendableBodyConsumer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class BasicAuthTest extends AbstractBasicTest {

    protected static final String MY_MESSAGE = "my message";

    private Server server2;
    private Server serverNoAuth;
    private int portNoAuth;

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        port1 = findFreePort();
        port2 = findFreePort();
        portNoAuth = findFreePort();

        server = newJettyHttpServer(port1);
        addBasicAuthHandler(server, configureHandler());
        server.start();

        server2 = newJettyHttpServer(port2);
        addDigestAuthHandler(server2, new RedirectHandler());
        server2.start();

        // need noAuth server to verify the preemptive auth mode (see basicAuthTetPreemtiveTest) 
        serverNoAuth = newJettyHttpServer(portNoAuth);
        serverNoAuth.setHandler(new SimpleHandler());
        serverNoAuth.start();

        logger.info("Local HTTP server started successfully");
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        server2.stop();
        serverNoAuth.stop();
    }

    @Override
    protected String getTargetUrl() {
        return "http://127.0.0.1:" + port1 + "/";
    }

    @Override
    protected String getTargetUrl2() {
        return "http://127.0.0.1:" + port2 + "/uff";
    }

    protected String getTargetUrlNoAuth() {
        return "http://127.0.0.1:" + portNoAuth + "/";
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new SimpleHandler();
    }

    private static class RedirectHandler extends AbstractHandler {

        private static final Logger LOGGER = LoggerFactory.getLogger(RedirectHandler.class);

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            LOGGER.info("request: " + request.getRequestURI());

            if ("/uff".equals(request.getRequestURI())) {
                LOGGER.info("redirect to /bla");
                response.setStatus(302);
                response.setContentLength(0);
                response.setHeader("Location", "/bla");

            } else {
                LOGGER.info("got redirected" + request.getRequestURI());
                response.setStatus(200);
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
                byte[] b = "content".getBytes(UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    private static class SimpleHandler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.setContentLength(0);

            } else {
                response.addHeader("X-Auth", request.getHeader("Authorization"));
                response.addHeader("X-Content-Length", String.valueOf(request.getContentLength()));
                response.setStatus(200);
    
                int size = 10 * 1024;
                if (request.getContentLength() > 0) {
                    size = request.getContentLength();
                }
                byte[] bytes = new byte[size];
                int contentLength = 0;
                if (bytes.length > 0) {
                    int read = request.getInputStream().read(bytes);
                    if (read > 0) {
                        contentLength = read;
                        response.getOutputStream().write(bytes, 0, read);
                    }
                }
                response.setContentLength(contentLength);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void redirectAndBasicAuthTest() throws Exception, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirect(true).setMaxRedirects(10).build())) {
            Future<Response> f = client.prepareGet(getTargetUrl2())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basic401Test() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl())//
                    .setHeader("X-401", "401")//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build());

            Future<Integer> f = r.execute(new AsyncHandler<Integer>() {

                private HttpResponseStatus status;

                public void onThrowable(Throwable t) {

                }

                public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                    return State.CONTINUE;
                }

                public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
                    this.status = responseStatus;

                    if (status.getStatusCode() != 200) {
                        return State.ABORT;
                    }
                    return State.CONTINUE;
                }

                public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                    return State.CONTINUE;
                }

                public Integer onCompleted() throws Exception {
                    return status.getStatusCode();
                }
            });
            Integer statusCode = f.get(10, TimeUnit.SECONDS);
            assertNotNull(statusCode);
            assertEquals(statusCode.intValue(), 401);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthTestPreemtiveTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
        	// send the request to the no-auth endpoint to be able to verify the auth header is
        	// really sent preemptively for the initial call.
            Future<Response> f = client.prepareGet(getTargetUrlNoAuth())//
                    .setRealm((new Realm.RealmBuilder()).setScheme(AuthScheme.BASIC).setPrincipal(USER).setPassword(ADMIN).setUsePreemptiveAuth(true).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthNegativeTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            Future<Response> f = client.prepareGet(getTargetUrl())//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal("fake").setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), 401);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthInputStreamTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(new ByteArrayInputStream("test".getBytes()))//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(30, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), "test");
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthFileTest() throws Exception {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE)//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthAsyncConfigTest() throws Exception {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build()).build())) {
            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE_STRING)//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void basicAuthFileNoKeepAliveTest() throws Exception {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setAllowPoolingConnections(false).build())) {

            Future<Response> f = client.preparePost(getTargetUrl())//
                    .setBody(SIMPLE_TEXT_FILE)//
                    .setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build())//
                    .execute();

            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void stringBuilderBodyConsumerTest() throws Exception {

        try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setRealmPrincipal(USER).setRealmPassword(ADMIN)
                .setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build()) {
            StringBuilder s = new StringBuilder();
            Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));

            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(s.toString(), MY_MESSAGE);
            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertNotNull(response.getHeader("X-Auth"));
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void noneAuthTest() throws IOException, ExecutionException, TimeoutException, InterruptedException {
        try (AsyncHttpClient client = getAsyncHttpClient(null)) {
            BoundRequestBuilder r = client.prepareGet(getTargetUrl()).setRealm((new Realm.RealmBuilder()).setPrincipal(USER).setPassword(ADMIN).build());

            Future<Response> f = r.execute();
            Response resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertNotNull(resp.getHeader("X-Auth"));
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        }
    }
}
