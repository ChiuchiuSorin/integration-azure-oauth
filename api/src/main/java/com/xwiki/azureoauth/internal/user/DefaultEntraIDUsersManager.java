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
package com.xwiki.azureoauth.internal.user;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.function.FailableConsumer;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;
import com.xwiki.azureoauth.configuration.EntraIDClientConfigurationStore;
import com.xwiki.azureoauth.internal.EntraIDApiClient;
import com.xwiki.azureoauth.user.EntraIDUsersManager;
import com.xwiki.azureoauth.user.ExternalUser;

import static com.xwiki.azureoauth.internal.configuration.DefaultEntraIDConfiguration.OIDC_USER_CLASS;

/**
 * Default implementation of {@link EntraIDUsersManager}.
 *
 * @version $Id$
 * @since 2.1
 */
@Component
@Singleton
public class DefaultEntraIDUsersManager implements EntraIDUsersManager
{
    private static final String ENTRA_ISSUER = "login.microsoftonline.com";

    // Read the subject and issuer from the database, to avoid loading the document of every user.
    private static final String USER_REFERENCES_QUERY = "select doc.fullName, oidc.issuer, oidc.subject "
        + "from Document doc, doc.object(" + OIDC_USER_CLASS + ") as oidc";

    @Inject
    private EntraIDApiClient entraIDApiClient;

    @Inject
    private EntraIDClientConfigurationStore configurationStore;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private QueryManager queryManager;

    @Inject
    private WikiDescriptorManager wikiManager;

    @Inject
    private Provider<XWikiContext> wikiContextProvider;

    @Inject
    @Named("document")
    private QueryFilter documentReferenceFilter;

    @Override
    public Map<String, XWikiDocument> getXWikiUsersMap() throws Exception
    {
        return getXWikiUsersMap(this.configurationStore.getDefaultConfiguration());
    }

    @Override
    public Map<String, XWikiDocument> getXWikiUsersMap(EntraIDClientConfiguration configuration)
        throws QueryException, XWikiException
    {
        XWikiContext wikiContext = this.wikiContextProvider.get();
        XWiki wiki = wikiContext.getWiki();

        Map<String, XWikiDocument> userMap = new HashMap<>();
        for (Map.Entry<String, DocumentReference> entry : getXWikiUserReferences(configuration).entrySet()) {
            userMap.put(entry.getKey(), wiki.getDocument(entry.getValue(), wikiContext));
        }
        return userMap;
    }

    @Override
    public Map<String, DocumentReference> getXWikiUserReferences(EntraIDClientConfiguration configuration)
        throws QueryException
    {
        List<Object[]> results = this.queryManager.createQuery(USER_REFERENCES_QUERY, Query.XWQL)
            .setWiki(this.wikiManager.getCurrentWikiId()).execute();

        Map<String, DocumentReference> userReferences = new HashMap<>();
        for (Object[] result : results) {
            if (configuration.matchesIssuer((String) result[1])) {
                userReferences.put((String) result[2], this.documentReferenceResolver.resolve((String) result[0]));
            }
        }
        return userReferences;
    }

    @Override
    public List<XWikiDocument> getXWikiUsers() throws XWikiException, QueryException
    {
        List<XWikiDocument> xwikiEntraUsers = new ArrayList<>();
        XWikiContext wikiContext = this.wikiContextProvider.get();
        XWiki wiki = wikiContext.getWiki();

        List<DocumentReference> results = this.queryManager.createQuery(
                ", BaseObject as obj where doc.fullName = obj.name and obj.className = :className", Query.HQL)
            .setWiki(this.wikiManager.getCurrentWikiId()).bindValue("className", OIDC_USER_CLASS)
            .addFilter(this.documentReferenceFilter).execute();

        for (DocumentReference userRef : results) {
            XWikiDocument userDoc = wiki.getDocument(userRef, wikiContext);
            BaseObject oidcObj = userDoc.getXObject(this.documentReferenceResolver.resolve(OIDC_USER_CLASS));
            String issuer = oidcObj.getField("issuer").toFormString();
            if (issuer.contains(ENTRA_ISSUER)) {
                xwikiEntraUsers.add(userDoc);
            }
        }
        return xwikiEntraUsers;
    }

    @Override
    public List<ExternalUser> getEntraServerUsers() throws Exception
    {
        return getEntraServerUsers(this.configurationStore.getDefaultConfiguration());
    }

    @Override
    public List<ExternalUser> getEntraServerUsers(EntraIDClientConfiguration configuration) throws Exception
    {
        List<ExternalUser> externalUsers = new ArrayList<>();
        forEachEntraServerUsersPage(configuration, externalUsers::addAll);
        return externalUsers;
    }

    @Override
    public void forEachEntraServerUsersPage(EntraIDClientConfiguration configuration,
        FailableConsumer<List<ExternalUser>, Exception> handler) throws Exception
    {
        this.entraIDApiClient.forEachUsersPage(configuration, handler);
    }
}
