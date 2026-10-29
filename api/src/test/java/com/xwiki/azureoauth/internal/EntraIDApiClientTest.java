/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.azureoauth.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;
import com.xwiki.azureoauth.user.ExternalUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EntraIDApiClient}
 *
 * @version $Id$
 */
@ComponentTest
class EntraIDApiClientTest
{
    private static final String USERS_API = "https://graph.microsoft.com/v1.0/users?$select=id,accountEnabled&$top=999";

    private static final String NEXT_LINK = USERS_API + "&$skiptoken=page2";

    private static final String GRAPH_ERROR = "Graph request did not return a valid response.";

    private final List<List<ExternalUser>> pages = new ArrayList<>();

    @Mock
    HttpClient httpClient;

    @InjectMockComponents
    private EntraIDApiClient entraIDApiClient;

    @MockComponent
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Mock
    private EntraIDClientConfiguration configuration;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private HttpResponse<String> httpResponse2;

    @Mock
    private HttpResponse<String> httpResponse3;

    @BeforeEach
    void setup() throws IOException, InterruptedException
    {
        when(this.configuration.getTokenEndpoint()).thenReturn("http://localhost:8080/some/token/url/");
        when(this.configuration.getClientID()).thenReturn("client_id");
        when(this.configuration.getSecret()).thenReturn("secret");
        when(this.httpClientBuilderFactory.getHttpClient()).thenReturn(this.httpClient);
        when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse);
    }

    @Test
    void forEachUsersPageFailToGetAccessToken()
    {
        when(this.httpResponse.statusCode()).thenReturn(500);
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);
        });
        assertEquals("Failed to get token", exception.getMessage());
    }

    @Test
    void forEachUsersPageGraphError() throws IOException, InterruptedException
    {
        mockAccessToken();
        when(this.httpClient.send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse2);
        when(this.httpResponse2.statusCode()).thenReturn(500);
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);
        });

        assertEquals(GRAPH_ERROR, exception.getMessage());
        assertEquals(List.of(), this.pages);
    }

    @Test
    void forEachUsersPage() throws Exception
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        String jsonBody = "{ \"access_token\": \"test-token-123\", \"value\": ["
            + "{ \"id\": \"user1\", \"accountEnabled\": true },{ \"id\": \"user2\", \"accountEnabled\": false },"
            + "{ \"id\": \"user3\", \"accountEnabled\": true }]}";
        when(this.httpResponse.body()).thenReturn(jsonBody);

        this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);

        assertEquals(1, this.pages.size());
        List<ExternalUser> users = this.pages.get(0);
        assertEquals("user1", users.get(0).getId());
        assertTrue(users.get(0).isEnabled());
        assertFalse(users.get(1).isEnabled());
        assertEquals("user3", users.get(2).getId());
    }

    @Test
    void forEachUsersPageWithPagination() throws Exception
    {
        mockAccessToken();
        mockGraphResponse(USERS_API, this.httpResponse2, 200, "{ \"value\": ["
            + "{ \"id\": \"user1\", \"accountEnabled\": true },{ \"id\": \"user2\", \"accountEnabled\": false }],"
            + " \"@odata.nextLink\": \"" + NEXT_LINK + "\" }");
        mockGraphResponse(NEXT_LINK, this.httpResponse3, 200,
            "{ \"value\": [{ \"id\": \"user3\", \"accountEnabled\": true }]}");

        this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);

        // Each page is handled separately.
        assertEquals(2, this.pages.size());
        assertEquals(2, this.pages.get(0).size());
        assertEquals("user1", this.pages.get(0).get(0).getId());
        assertFalse(this.pages.get(0).get(1).isEnabled());
        assertEquals(1, this.pages.get(1).size());
        assertEquals("user3", this.pages.get(1).get(0).getId());
    }

    @Test
    void forEachUsersPageGraphErrorOnNextPage() throws Exception
    {
        mockAccessToken();
        mockGraphResponse(USERS_API, this.httpResponse2, 200,
            "{ \"value\": [{ \"id\": \"user1\", \"accountEnabled\": true }]," + " \"@odata.nextLink\": \"" + NEXT_LINK
                + "\" }");
        mockGraphResponse(NEXT_LINK, this.httpResponse3, 500, null);

        // A failing page must fail the whole call, so that the callers know that some users are missing.
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);
        });
        assertEquals(GRAPH_ERROR, exception.getMessage());
        assertEquals(1, this.pages.size());
    }

    @Test
    void forEachUsersPageRetriesWhenThrottled() throws Exception
    {
        mockAccessToken();
        when(this.httpClient.send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse2, this.httpResponse3);
        when(this.httpResponse2.statusCode()).thenReturn(429);
        when(this.httpResponse2.headers()).thenReturn(getRetryAfterHeaders("0"));
        when(this.httpResponse3.statusCode()).thenReturn(200);
        when(this.httpResponse3.body()).thenReturn("{ \"value\": [{ \"id\": \"user1\", \"accountEnabled\": true }]}");

        this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);

        assertEquals(1, this.pages.size());
        assertEquals("user1", this.pages.get(0).get(0).getId());
    }

    @Test
    void forEachUsersPageRetriesWithoutRetryAfter() throws Exception
    {
        mockAccessToken();
        when(this.httpClient.send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse2, this.httpResponse3);
        when(this.httpResponse2.statusCode()).thenReturn(503);
        when(this.httpResponse2.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(this.httpResponse3.statusCode()).thenReturn(200);
        when(this.httpResponse3.body()).thenReturn("{ \"value\": []}");

        this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);

        assertEquals(1, this.pages.size());
        assertEquals(List.of(), this.pages.get(0));
    }

    @Test
    void forEachUsersPageStopsRetrying() throws Exception
    {
        mockAccessToken();
        mockGraphResponse(USERS_API, this.httpResponse2, 429, null);
        when(this.httpResponse2.headers()).thenReturn(getRetryAfterHeaders("0"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.forEachUsersPage(this.configuration, this.pages::add);
        });

        assertEquals(GRAPH_ERROR, exception.getMessage());
        // The first attempt and 5 retries.
        verify(this.httpClient, times(6)).send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class));
        assertEquals(List.of(), this.pages);
    }

    private void mockAccessToken()
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        when(this.httpResponse.body()).thenReturn("{ \"access_token\": \"test-token-123\" }");
    }

    private void mockGraphResponse(String url, HttpResponse<String> response, int statusCode, String body)
        throws IOException, InterruptedException
    {
        when(this.httpClient.send(eq(getGraphRequest(url)), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
    }

    private HttpHeaders getRetryAfterHeaders(String retryAfter)
    {
        return HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)), (name, value) -> true);
    }

    private HttpRequest getGraphRequest(String url)
    {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer test-token-123").GET()
            .build();
    }
}
