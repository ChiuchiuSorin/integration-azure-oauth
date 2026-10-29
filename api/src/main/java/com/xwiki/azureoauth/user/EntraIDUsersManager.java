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
package com.xwiki.azureoauth.user;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.function.FailableConsumer;
import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.QueryException;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;

/**
 * Expose the EntraID users which are already present on the XWiki instance, or the list which is provided by the
 * EntraID server.
 *
 * @version $Id$
 * @since 2.1
 */
@Role
@Unstable
public interface EntraIDUsersManager
{
    /**
     * Get a {@link Map} of internal users that are created from the tenant of the default configuration, with the user
     * id (subject) as the key.
     *
     * @return the internal users that are created from the tenant of the default configuration
     * @throws Exception if there are any errors while getting the users.
     * @deprecated since 2.3.0, use {@link #getXWikiUsersMap(EntraIDClientConfiguration)} instead, since the users can
     *     be created from several tenants
     */
    @Deprecated(since = "2.3.0")
    Map<String, XWikiDocument> getXWikiUsersMap() throws Exception;

    /**
     * Get a {@link List} of internal users that are created from EntraId, whatever their tenant.
     *
     * @return a {@link List} of the user documents
     * @throws XWikiException if there is any error while retrieving the documents
     * @throws QueryException if the query execution fails
     */
    List<XWikiDocument> getXWikiUsers() throws XWikiException, QueryException;

    /**
     * Get a {@link List} of users present on the EntraID server of the default configuration.
     *
     * @return the external users info stored in {@link ExternalUser}
     * @throws Exception if there are any errors while getting the users.
     * @deprecated since 2.3.0, use {@link #getEntraServerUsers(EntraIDClientConfiguration)} instead
     */
    @Deprecated(since = "2.3.0")
    List<ExternalUser> getEntraServerUsers() throws Exception;

    /**
     * Get a {@link Map} of internal users that are created from the tenant of the given configuration, with the user id
     * (subject) as the key.
     *
     * @param configuration the Entra ID client configuration the users were created from
     * @return the internal users that are created from the tenant of the given configuration
     * @throws Exception if there are any errors while getting the users.
     * @since 2.3.0
     */
    Map<String, XWikiDocument> getXWikiUsersMap(EntraIDClientConfiguration configuration) throws Exception;

    /**
     * Get a {@link List} of users present on the Entra ID server of the given configuration.
     *
     * @param configuration the Entra ID client configuration used to query the server
     * @return the external users info stored in {@link ExternalUser}
     * @throws Exception if there are any errors while getting the users.
     * @since 2.3.0
     */
    List<ExternalUser> getEntraServerUsers(EntraIDClientConfiguration configuration) throws Exception;

    /**
     * Get the references of the internal users that are created from the tenant of the given configuration, with the
     * user id (subject) as the key. Unlike {@link #getXWikiUsersMap(EntraIDClientConfiguration)}, the user documents
     * are not loaded.
     *
     * @param configuration the Entra ID client configuration the users were created from
     * @return the references of the internal users that are created from the tenant of the given configuration
     * @throws QueryException if the query execution fails
     * @since 2.3.0
     */
    Map<String, DocumentReference> getXWikiUserReferences(EntraIDClientConfiguration configuration)
        throws QueryException;

    /**
     * Pass the users present on the Entra ID server of the given configuration to the given handler, one page at a
     * time, so that they don't have to be held in memory all at once. If a page can't be retrieved, an exception is
     * thrown and the next pages are not handled.
     *
     * @param configuration the Entra ID client configuration used to query the server
     * @param handler the handler called for each page of users
     * @throws Exception if there are any errors while getting or handling the users
     * @since 2.3.0
     */
    void forEachEntraServerUsersPage(EntraIDClientConfiguration configuration,
        FailableConsumer<List<ExternalUser>, Exception> handler) throws Exception;
}
