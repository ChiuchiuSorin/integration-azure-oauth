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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EntraIDApiClient}
 *
 * @version $Id$
 */
@ComponentTest
class EntraIDApiClientTest
{
    private static final String USERS_API = "https://graph.microsoft.com/v1.0/users?$select=id,accountEnabled";

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
    void getEntraUsersJsonMapTestFailToGetAccessToken()
    {
        when(this.httpResponse.statusCode()).thenReturn(500);
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.getUsers(this.configuration);
        });
        assertEquals("Failed to get token", exception.getMessage());
    }

    @Test
    void getEntraUsersJsonMapTestGraphError() throws IOException, InterruptedException
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        String jsonBody = "{ \"access_token\": \"test-token-123\" }";

        when(this.httpResponse.body()).thenReturn(jsonBody);
        when(this.httpClient.send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse2);
        when(this.httpResponse2.statusCode()).thenReturn(500);
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.getUsers(this.configuration);
        });

        assertEquals("Graph request did not return a valid response.", exception.getMessage());
    }

    @Test
    void getEntraUsersJsonMapTest() throws Exception
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        String jsonBody = "{ \"access_token\": \"test-token-123\", \"value\": ["
            + "{ \"id\": \"user1\", \"accountEnabled\": true },{ \"id\": \"user2\", \"accountEnabled\": false },"
            + "{ \"id\": \"user3\", \"accountEnabled\": true }]}";

        when(this.httpResponse.body()).thenReturn(jsonBody);
        JSONArray jsonArray = this.entraIDApiClient.getUsers(this.configuration);
        assertEquals("user1", jsonArray.getJSONObject(0).optString("id"));
        assertFalse(jsonArray.getJSONObject(1).optBoolean("accountEnabled"));
        assertEquals("user3", jsonArray.getJSONObject(2).optString("id"));
    }

    @Test
    void getEntraUsersJsonMapTestWithPagination() throws Exception
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        when(this.httpResponse.body()).thenReturn("{ \"access_token\": \"test-token-123\" }");

        String nextLink = USERS_API + "&$skiptoken=page2";
        when(this.httpClient.send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse2);
        when(this.httpResponse2.statusCode()).thenReturn(200);
        when(this.httpResponse2.body()).thenReturn("{ \"value\": ["
            + "{ \"id\": \"user1\", \"accountEnabled\": true },{ \"id\": \"user2\", \"accountEnabled\": false }],"
            + " \"@odata.nextLink\": \"" + nextLink + "\" }");

        when(this.httpClient.send(eq(getGraphRequest(nextLink)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse3);
        when(this.httpResponse3.statusCode()).thenReturn(200);
        when(this.httpResponse3.body()).thenReturn("{ \"value\": [{ \"id\": \"user3\", \"accountEnabled\": true }]}");

        JSONArray jsonArray = this.entraIDApiClient.getUsers(this.configuration);
        assertEquals(3, jsonArray.length());
        assertEquals("user1", jsonArray.getJSONObject(0).optString("id"));
        assertFalse(jsonArray.getJSONObject(1).optBoolean("accountEnabled"));
        assertEquals("user3", jsonArray.getJSONObject(2).optString("id"));
    }

    @Test
    void getEntraUsersJsonMapTestGraphErrorOnNextPage() throws Exception
    {
        when(this.httpResponse.statusCode()).thenReturn(200);
        when(this.httpResponse.body()).thenReturn("{ \"access_token\": \"test-token-123\" }");

        String nextLink = USERS_API + "&$skiptoken=page2";
        when(this.httpClient.send(eq(getGraphRequest(USERS_API)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse2);
        when(this.httpResponse2.statusCode()).thenReturn(200);
        when(this.httpResponse2.body()).thenReturn(
            "{ \"value\": [{ \"id\": \"user1\", \"accountEnabled\": true }]," + " \"@odata.nextLink\": \"" + nextLink
                + "\" }");

        when(this.httpClient.send(eq(getGraphRequest(nextLink)), any(HttpResponse.BodyHandler.class))).thenReturn(
            this.httpResponse3);
        when(this.httpResponse3.statusCode()).thenReturn(500);

        // A failing page must fail the whole call, otherwise the sync would remove the users of the missing pages.
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            this.entraIDApiClient.getUsers(this.configuration);
        });
        assertEquals("Graph request did not return a valid response.", exception.getMessage());
    }

    private HttpRequest getGraphRequest(String url)
    {
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer test-token-123").GET()
            .build();
    }
}
