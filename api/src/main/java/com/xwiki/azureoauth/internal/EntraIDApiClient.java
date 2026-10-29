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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableConsumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xwiki.component.annotation.Component;

import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;
import com.xwiki.azureoauth.user.ExternalUser;

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

    /**
     * 999 is the maximum page size Graph allows for users, the default being 100, as documented in <a
     * href="https://learn.microsoft.com/en-us/graph/api/user-list?view=graph-rest-1.0#optional-query-parameters"> Graph
     * documentation</a>.
     */
    private static final String USERS_API = "https://graph.microsoft.com/v1.0/users?$select=id,accountEnabled&$top=999";

    private static final int STATUS_OK = 200;

    // Graph answers with these when it throttles the requests or is temporarily unavailable.
    private static final List<Integer> RETRY_STATUSES = List.of(429, 503);

    private static final int MAX_RETRIES = 5;

    @Inject
    private HttpClientBuilderFactory httpClientBuilderFactory;

    /**
     * Pass the users from the Entra ID application of the given configuration to the given handler, one page at a time.
     * If a page can't be retrieved, an exception is thrown and the next pages are not handled.
     *
     * @param clientConfiguration the configuration of the Entra ID application to query
     * @param handler the handler called for each page of users
     * @throws Exception if any error occurs
     * @since 2.3.0
     */
    public void forEachUsersPage(EntraIDClientConfiguration clientConfiguration,
        FailableConsumer<List<ExternalUser>, Exception> handler) throws Exception
    {
        String accessToken = getAccessToken(clientConfiguration.getTokenEndpoint(), clientConfiguration.getClientID(),
            clientConfiguration.getSecret());
        HttpClient client = this.httpClientBuilderFactory.getHttpClient();

        String nextLink = USERS_API;
        while (StringUtils.isNotBlank(nextLink)) {
            JSONObject page = getUsersPage(client, accessToken, URI.create(nextLink));
            handler.accept(toExternalUsers(page.getJSONArray("value")));
            nextLink = page.has(ODATA_NEXT_LINK) ? page.getString(ODATA_NEXT_LINK) : null;
        }
    }

    private List<ExternalUser> toExternalUsers(JSONArray users)
    {
        List<ExternalUser> externalUsers = new ArrayList<>();
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.getJSONObject(i);
            externalUsers.add(new ExternalUser(user.optString("id"), user.optBoolean("accountEnabled")));
        }
        return externalUsers;
    }

    private JSONObject getUsersPage(HttpClient client, String accessToken, URI uri)
        throws IOException, InterruptedException
    {
        HttpRequest request =
            HttpRequest.newBuilder().uri(uri).header("Authorization", "Bearer " + accessToken).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        for (int retry = 1; RETRY_STATUSES.contains(response.statusCode()) && retry <= MAX_RETRIES; retry++) {
            Thread.sleep(getRetryDelay(response, retry));
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != STATUS_OK) {
            throw new RuntimeException("Graph request did not return a valid response.");
        }
        return new JSONObject(response.body());
    }

    private long getRetryDelay(HttpResponse<String> response, int retry)
    {
        // Graph sends the number of seconds to wait before retrying. Without it, back off exponentially.
        try {
            OptionalLong retryAfter = response.headers().firstValueAsLong("Retry-After");
            if (retryAfter.isPresent() && retryAfter.getAsLong() >= 0) {
                return TimeUnit.SECONDS.toMillis(retryAfter.getAsLong());
            }
        } catch (NumberFormatException e) {
            // Retry-After can also be an HTTP date, which Graph doesn't use, so fall back to the exponential delay.
        }
        return TimeUnit.SECONDS.toMillis(1L << (retry - 1));
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

        if (response.statusCode() != STATUS_OK) {
            throw new RuntimeException("Failed to get token");
        }

        JSONObject json = new JSONObject(response.body());
        return json.getString("access_token");
    }
}
