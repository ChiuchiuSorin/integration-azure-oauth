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
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.lang3.function.FailableConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.PropertyInterface;
import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;
import com.xwiki.azureoauth.configuration.EntraIDClientConfigurationStore;
import com.xwiki.azureoauth.internal.EntraIDApiClient;
import com.xwiki.azureoauth.user.ExternalUser;

import static com.xwiki.azureoauth.internal.configuration.DefaultEntraIDConfiguration.OIDC_USER_CLASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DefaultEntraIDUsersManager}
 *
 * @version $Id$
 */
@ComponentTest
class DefaultEntraIDUsersManagerTest
{
    @InjectMockComponents
    private DefaultEntraIDUsersManager defaultEntraIDUsersManager;

    @MockComponent
    private EntraIDApiClient entraIDApiClient;

    @MockComponent
    private EntraIDClientConfigurationStore configurationStore;

    @Mock
    private EntraIDClientConfiguration defaultConfiguration;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Mock
    private XWikiDocument userDoc1;

    @Mock
    private XWikiDocument userDoc2;

    @Mock
    private XWikiDocument userDoc3;

    @Mock
    private PropertyInterface property4;

    @Mock
    private PropertyInterface property5;

    @Mock
    private PropertyInterface property6;

    @Mock
    private BaseObject object1;

    @Mock
    private BaseObject object2;

    @Mock
    private BaseObject object3;

    @Mock
    private DocumentReference oidcUserClassDocRef;

    @MockComponent
    private WikiDescriptorManager wikiManager;

    @MockComponent
    private QueryManager queryManager;

    @MockComponent
    private Provider<XWikiContext> wikiContextProvider;

    @MockComponent
    private XWikiContext wikiContext;

    @MockComponent
    @Named("document")
    private QueryFilter documentReferenceFilter;

    @Mock
    private DocumentReference documentReference1;

    @Mock
    private DocumentReference documentReference2;

    @Mock
    private DocumentReference documentReference3;

    @Mock
    private XWikiDocument wikiDocument1;

    @Mock
    private XWikiDocument wikiDocument2;

    @Mock
    private Query query;

    @Mock
    private Query referencesQuery;

    @Mock
    private XWiki wiki;

    @BeforeEach
    void setup() throws Exception
    {
        when(configurationStore.getDefaultConfiguration()).thenReturn(defaultConfiguration);
        when(wikiContextProvider.get()).thenReturn(wikiContext);
        when(wikiContext.getWiki()).thenReturn(wiki);
        when(
            queryManager.createQuery(", BaseObject as obj where doc.fullName = obj.name and obj.className = :className",
                Query.HQL)).thenReturn(query);
        when(wikiManager.getCurrentWikiId()).thenReturn("wiki_id");
        when(query.setWiki("wiki_id")).thenReturn(query);
        when(query.bindValue("className", "XWiki.OIDC.UserClass")).thenReturn(query);
        when(query.addFilter(documentReferenceFilter)).thenReturn(query);
        when(query.execute()).thenReturn(List.of(documentReference1, documentReference2, documentReference3));
        when(wikiContext.getWiki()).thenReturn(wiki);

        when(wiki.getDocument(documentReference1, wikiContext)).thenReturn(userDoc1);
        when(wiki.getDocument(documentReference2, wikiContext)).thenReturn(userDoc2);
        when(wiki.getDocument(documentReference3, wikiContext)).thenReturn(userDoc3);

        when(documentReferenceResolver.resolve(OIDC_USER_CLASS)).thenReturn(oidcUserClassDocRef);
        when(userDoc1.getXObject(oidcUserClassDocRef)).thenReturn(object1);
        when(userDoc2.getXObject(oidcUserClassDocRef)).thenReturn(object2);
        when(userDoc3.getXObject(oidcUserClassDocRef)).thenReturn(object3);
        when(object1.getField("issuer")).thenReturn(property4);
        when(object2.getField("issuer")).thenReturn(property5);
        when(object3.getField("issuer")).thenReturn(property6);
        when(property4.toFormString()).thenReturn("https://login.microsoftonline.com/etc");
        when(property5.toFormString()).thenReturn("issuer2");
        when(property6.toFormString()).thenReturn("https://login.microsoftonline.com/etc");

        when(queryManager.createQuery("select doc.fullName, oidc.issuer, oidc.subject "
            + "from Document doc, doc.object(XWiki.OIDC.UserClass) as oidc", Query.XWQL)).thenReturn(referencesQuery);
        when(referencesQuery.setWiki("wiki_id")).thenReturn(referencesQuery);
        when(referencesQuery.execute()).thenReturn(
            List.of(new Object[] { "XWiki.User1", "https://login.microsoftonline.com/tenant/v2.0", "subject1" },
                new Object[] { "XWiki.User2", "issuer2", "subject2" },
                new Object[] { "XWiki.User3", "https://login.microsoftonline.com/other/v2.0", "subject3" }));
        when(documentReferenceResolver.resolve("XWiki.User1")).thenReturn(documentReference1);
        when(documentReferenceResolver.resolve("XWiki.User2")).thenReturn(documentReference2);
        when(documentReferenceResolver.resolve("XWiki.User3")).thenReturn(documentReference3);
        when(defaultConfiguration.matchesIssuer("https://login.microsoftonline.com/tenant/v2.0")).thenReturn(true);
    }

    @Test
    void getEntraServerUsersTest() throws Exception
    {
        mockEntraServerUsersPages(defaultConfiguration,
            List.of(new ExternalUser("user1", true), new ExternalUser("user2", false),
                new ExternalUser("user3", true)));
        List<ExternalUser> externalUsers = defaultEntraIDUsersManager.getEntraServerUsers();
        assertEquals("user1", externalUsers.get(0).getId());
        assertFalse(externalUsers.get(1).isEnabled());
        assertEquals("user3", externalUsers.get(2).getId());
    }

    @Test
    void getEntraUsersMapTest() throws Exception
    {
        // Only the users of the default configuration's tenant are returned.
        assertEquals(Map.of("subject1", userDoc1), defaultEntraIDUsersManager.getXWikiUsersMap());
    }

    @Test
    void getEntraUsersMapForConfiguration() throws Exception
    {
        EntraIDClientConfiguration configuration = mock(EntraIDClientConfiguration.class);
        when(configuration.matchesIssuer("https://login.microsoftonline.com/other/v2.0")).thenReturn(true);

        assertEquals(Map.of("subject3", userDoc3), defaultEntraIDUsersManager.getXWikiUsersMap(configuration));
    }

    @Test
    void getXWikiUserReferences() throws Exception
    {
        EntraIDClientConfiguration configuration = mock(EntraIDClientConfiguration.class);
        when(configuration.matchesIssuer("https://login.microsoftonline.com/tenant/v2.0")).thenReturn(true);
        when(configuration.matchesIssuer("https://login.microsoftonline.com/other/v2.0")).thenReturn(true);

        assertEquals(Map.of("subject1", documentReference1, "subject3", documentReference3),
            defaultEntraIDUsersManager.getXWikiUserReferences(configuration));
        // The user documents are not loaded.
        verify(wiki, never()).getDocument(any(DocumentReference.class), any(XWikiContext.class));
    }

    @Test
    void getEntraServerUsersForConfiguration() throws Exception
    {
        EntraIDClientConfiguration configuration = mock(EntraIDClientConfiguration.class);
        mockEntraServerUsersPages(configuration, List.of(new ExternalUser("user1", false)),
            List.of(new ExternalUser("user2", true)));

        List<ExternalUser> externalUsers = defaultEntraIDUsersManager.getEntraServerUsers(configuration);

        assertEquals(2, externalUsers.size());
        assertEquals("user1", externalUsers.get(0).getId());
        assertFalse(externalUsers.get(0).isEnabled());
        assertEquals("user2", externalUsers.get(1).getId());
        assertTrue(externalUsers.get(1).isEnabled());
    }

    @Test
    void forEachEntraServerUsersPage() throws Exception
    {
        EntraIDClientConfiguration configuration = mock(EntraIDClientConfiguration.class);
        mockEntraServerUsersPages(configuration, List.of(new ExternalUser("user1", false)),
            List.of(new ExternalUser("user2", true)));

        List<List<ExternalUser>> pages = new ArrayList<>();
        defaultEntraIDUsersManager.forEachEntraServerUsersPage(configuration, pages::add);

        assertEquals(2, pages.size());
        assertEquals("user1", pages.get(0).get(0).getId());
        assertFalse(pages.get(0).get(0).isEnabled());
        assertEquals("user2", pages.get(1).get(0).getId());
        assertTrue(pages.get(1).get(0).isEnabled());
    }

    @Test
    void getEntraUsersTest() throws QueryException, XWikiException
    {
        assertEquals(List.of(userDoc1, userDoc3), defaultEntraIDUsersManager.getXWikiUsers());
    }

    @SafeVarargs
    private void mockEntraServerUsersPages(EntraIDClientConfiguration configuration, List<ExternalUser>... pages)
        throws Exception
    {
        doAnswer(invocation -> {
            FailableConsumer<List<ExternalUser>, Exception> handler = invocation.getArgument(1);
            for (List<ExternalUser> page : pages) {
                handler.accept(page);
            }
            return null;
        }).when(entraIDApiClient).forEachUsersPage(eq(configuration), any());
    }
}
