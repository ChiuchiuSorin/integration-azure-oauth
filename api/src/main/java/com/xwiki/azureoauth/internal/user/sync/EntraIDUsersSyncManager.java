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
package com.xwiki.azureoauth.internal.user.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;
import com.xwiki.azureoauth.configuration.EntraIDClientConfigurationStore;
import com.xwiki.azureoauth.user.EntraIDUsersManager;
import com.xwiki.azureoauth.user.ExternalUser;

/**
 * Manage the sync between Entra ID users and XWiki users.
 *
 * @version $Id$
 * @since 2.1
 */
@Component(roles = EntraIDUsersSyncManager.class)
@Singleton
public class EntraIDUsersSyncManager
{
    private static final String USER_CLASS = "XWiki.XWikiUsers";

    private static final String SAVE_MESSAGE = "Disable user during EntraID user synchronization";

    @Inject
    private Provider<XWikiContext> wikiContextProvider;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private EntraIDUsersManager usersManager;

    @Inject
    private EntraIDClientConfigurationStore configurationStore;

    /**
     * Synchronize the state of the XWiki users created from the tenant of the default configuration with their state
     * from the Microsoft server.
     *
     * @param disable {@code true} if the sync should also sync disabled users, or {@code false} otherwise
     * @param remove {@code true} if the sync should also sync removed users, or {@code false} otherwise
     * @throws Exception if any error occurs during the sync
     */
    public void syncUsers(boolean disable, boolean remove) throws Exception
    {
        syncUsers(this.configurationStore.getDefaultConfiguration(), disable, remove);
    }

    /**
     * Synchronize the state of the XWiki users created from the tenant of the given configuration with their state from
     * the Microsoft server. The users created from other tenants are left untouched. Removing users is refused when the
     * configuration doesn't target a single tenant, since its users can't be told apart from the users of other
     * tenants.
     *
     * @param configuration the Entra ID client configuration to synchronize the users of
     * @param disable {@code true} if the sync should also sync disabled users, or {@code false} otherwise
     * @param remove {@code true} if the sync should also sync removed users, or {@code false} otherwise
     * @throws Exception if any error occurs during the sync
     * @throws IllegalArgumentException if users should be removed and the configuration doesn't target a single
     *     tenant
     * @since 2.3.0
     */
    public void syncUsers(EntraIDClientConfiguration configuration, boolean disable, boolean remove) throws Exception
    {
        if (remove && !configuration.hasSpecificTenant()) {
            throw new IllegalArgumentException(String.format(
                "Cannot remove the users of the Entra ID client configuration [%s] because it doesn't target a "
                    + "single tenant.", configuration.getConfigurationName()));
        }
        // The users that the Entra ID server doesn't return have been removed from it.
        Map<String, DocumentReference> missingUsers =
            new HashMap<>(this.usersManager.getXWikiUserReferences(configuration));
        this.usersManager.forEachEntraServerUsersPage(configuration,
            externalUsers -> syncUsersPage(externalUsers, missingUsers, disable));

        // This is reached only once every page was handled, otherwise the users of a missing page would be removed.
        if (remove) {
            XWikiContext wikiContext = this.wikiContextProvider.get();
            XWiki wiki = wikiContext.getWiki();
            for (DocumentReference userReference : missingUsers.values()) {
                wiki.deleteDocument(wiki.getDocument(userReference, wikiContext), wikiContext);
            }
        }
    }

    private void syncUsersPage(List<ExternalUser> externalUsers, Map<String, DocumentReference> missingUsers,
        boolean disable) throws XWikiException
    {
        for (ExternalUser externalUser : externalUsers) {
            DocumentReference userReference = missingUsers.remove(externalUser.getId());
            if (userReference != null && disable && !externalUser.isEnabled()) {
                disableUser(userReference);
            }
        }
    }

    private void disableUser(DocumentReference userReference) throws XWikiException
    {
        XWikiContext wikiContext = this.wikiContextProvider.get();
        XWiki wiki = wikiContext.getWiki();
        XWikiDocument userDoc = wiki.getDocument(userReference, wikiContext);
        BaseObject userObj = userDoc.getXObject(this.documentReferenceResolver.resolve(USER_CLASS));
        userObj.set("active", 0, wikiContext);
        wiki.saveDocument(userDoc, SAVE_MESSAGE, wikiContext);
    }
}
