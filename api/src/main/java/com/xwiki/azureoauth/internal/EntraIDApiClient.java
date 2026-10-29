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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xwiki.component.annotation.Component;

import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;

/**
 * Client for interacting with Microsoft Entra ID, including access token acquisition and retrieval of identity data via
 * Entra ID APIs.
 *
 * @version $Id$
 * @since 2.1
 */
@Component(roles = EntraIDApiClient.class)
@Singleton
public class EntraIDApiClient
{
    private static final String ODATA_NEXT_LINK = "@odata.nextLink";

    private static final String USERS_API = "https://graph.microsoft.com/v1.0/users?$select=id,accountEnabled";

    @Inject
    private HttpClientBuilderFactory httpClientBuilderFactory;

    /**
     * Get the users from the Entra ID application of the given configuration.
     *
     * @param clientConfiguration the configuration of the Entra ID application to query
     * @return a {@link JSONArray} consisting of the user ID and whether the account is enabled or not.
     * @throws Exception if any error occurs
     * @since 2.3.0
     */
    public JSONArray getUsers(EntraIDClientConfiguration clientConfiguration) throws Exception
    {
        String accessToken = getAccessToken(clientConfiguration.getTokenEndpoint(), clientConfiguration.getClientID(),
            clientConfiguration.getSecret());
        HttpClient client = this.httpClientBuilderFactory.getHttpClient();

        JSONArray users = new JSONArray();
        String nextLink = USERS_API;
        while (!StringUtils.isBlank(nextLink)) {
            JSONObject page = getUsersPage(client, accessToken, URI.create(nextLink));
            JSONArray pageUsers = page.getJSONArray("value");
            for (int i = 0; i < pageUsers.length(); i++) {
                users.put(pageUsers.get(i));
            }
            nextLink = page.has(ODATA_NEXT_LINK) ? page.getString(ODATA_NEXT_LINK) : null;
        }
        return users;
    }

    private JSONObject getUsersPage(HttpClient client, String accessToken, URI uri)
        throws IOException, InterruptedException
    {
        HttpRequest request =
            HttpRequest.newBuilder().uri(uri).header("Authorization", "Bearer " + accessToken).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Graph request did not return a valid response.");
        }
        return new JSONObject(response.body());
    }

    private String getAccessToken(String url, String clientID, String secret) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        sb.append("client_id=").append(URLEncoder.encode(clientID, StandardCharsets.UTF_8));
        sb.append("&scope=").append(URLEncoder.encode("https://graph.microsoft.com/.default", StandardCharsets.UTF_8));
        sb.append("&client_secret=").append(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        sb.append("&grant_type=client_credentials");

        HttpRequest request =
            HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
        HttpClient client = this.httpClientBuilderFactory.getHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get token");
        }

        JSONObject json = new JSONObject(response.body());
        return json.getString("access_token");
    }
}
