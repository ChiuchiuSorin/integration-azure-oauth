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
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xwiki.azureoauth.configuration.EntraIDClientConfiguration;
import com.xwiki.azureoauth.configuration.EntraIDClientConfigurationStore;
import com.xwiki.azureoauth.user.EntraIDUsersManager;
import com.xwiki.azureoauth.user.ExternalUser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EntraIDUsersSyncManagerTest}
 *
 * @version $Id$
 */
@ComponentTest
class EntraIDUsersSyncManagerTest
{
    @InjectMockComponents
    private EntraIDUsersSyncManager syncManager;

    @MockComponent
    private EntraIDUsersManager entraIdUsersManager;

    @MockComponent
    private EntraIDClientConfigurationStore configurationStore;

    @MockComponent
    private Provider<XWikiContext> wikiContextProvider;

    @MockComponent
    private WikiDescriptorManager wikiManager;

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Mock
    private XWikiContext wikiContext;

    @Mock
    private XWiki wiki;

    @Mock
    private DocumentReference userClassDocRef;

    @Mock
    private DocumentReference userRef1;

    @Mock
    private DocumentReference userRef2;

    @Mock
    private DocumentReference userRef3;

    @Mock
    private XWikiDocument userDoc1;

    @Mock
    private XWikiDocument userDoc2;

    @Mock
    private XWikiDocument userDoc3;

    @Mock
    private BaseObject objectUserClass2;

    @Mock
    private EntraIDClientConfiguration defaultConfiguration;

    @BeforeEach
    void setUp() throws Exception
    {
        when(wikiContextProvider.get()).thenReturn(wikiContext);
        when(wikiContext.getWiki()).thenReturn(wiki);
        when(wikiManager.getCurrentWikiId()).thenReturn("testWiki");
        when(configurationStore.getDefaultConfiguration()).thenReturn(defaultConfiguration);
        when(defaultConfiguration.hasSpecificTenant()).thenReturn(true);
        when(entraIdUsersManager.getXWikiUserReferences(defaultConfiguration)).thenReturn(
            Map.of("subject1", userRef1, "subject2", userRef2, "subject3", userRef3));
        mockEntraServerUsersPages(defaultConfiguration,
            List.of(new ExternalUser("subject1", true), new ExternalUser("subject2", false)));
        when(wiki.getDocument(userRef1, wikiContext)).thenReturn(userDoc1);
        when(wiki.getDocument(userRef2, wikiContext)).thenReturn(userDoc2);
        when(wiki.getDocument(userRef3, wikiContext)).thenReturn(userDoc3);

        when(documentReferenceResolver.resolve("XWiki.XWikiUsers")).thenReturn(userClassDocRef);
        when(userDoc2.getXObject(userClassDocRef)).thenReturn(objectUserClass2);
    }

    @Test
    void syncUsersTestDisableRemove() throws Exception
    {
        syncManager.syncUsers(true, true);
        verify(wiki, times(1)).deleteDocument(userDoc3, wikiContext);
        verify(objectUserClass2, times(1)).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersTestDisable() throws Exception
    {
        syncManager.syncUsers(true, false);
        verify(wiki, times(0)).deleteDocument(userDoc3, wikiContext);
        verify(objectUserClass2, times(1)).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersForConfiguration() throws Exception
    {
        EntraIDClientConfiguration configuration = mock(EntraIDClientConfiguration.class);
        when(configuration.hasSpecificTenant()).thenReturn(true);
        when(entraIdUsersManager.getXWikiUserReferences(configuration)).thenReturn(
            Map.of("subject1", userRef1, "subject3", userRef3));
        mockEntraServerUsersPages(configuration, List.of(new ExternalUser("subject1", false)));
        BaseObject objectUserClass1 = mock(BaseObject.class);
        when(userDoc1.getXObject(userClassDocRef)).thenReturn(objectUserClass1);

        syncManager.syncUsers(configuration, true, true);

        verify(wiki).deleteDocument(userDoc3, wikiContext);
        verify(objectUserClass1).set("active", 0, wikiContext);
        verify(wiki, never()).deleteDocument(userDoc2, wikiContext);
        verify(objectUserClass2, never()).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersTestRemove() throws Exception
    {
        syncManager.syncUsers(false, true);
        verify(wiki, times(1)).deleteDocument(userDoc3, wikiContext);
        verify(objectUserClass2, times(0)).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersRefusesRemoveWithoutSpecificTenant() throws Exception
    {
        when(defaultConfiguration.hasSpecificTenant()).thenReturn(false);
        when(defaultConfiguration.getConfigurationName()).thenReturn("multiTenant");

        IllegalArgumentException exception =
            assertThrows(IllegalArgumentException.class, () -> syncManager.syncUsers(true, true));

        assertEquals("Cannot remove the users of the Entra ID client configuration [multiTenant] because it doesn't "
            + "target a single tenant.", exception.getMessage());
        verify(wiki, never()).deleteDocument(any(), any());
        verify(objectUserClass2, never()).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersDisablesWithoutSpecificTenant() throws Exception
    {
        when(defaultConfiguration.hasSpecificTenant()).thenReturn(false);

        syncManager.syncUsers(true, false);

        verify(wiki, never()).deleteDocument(any(), any());
        verify(objectUserClass2).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersWithSeveralPages() throws Exception
    {
        mockEntraServerUsersPages(defaultConfiguration, List.of(new ExternalUser("subject1", true)),
            List.of(new ExternalUser("subject2", false)));

        syncManager.syncUsers(true, true);

        verify(wiki).deleteDocument(userDoc3, wikiContext);
        verify(wiki, never()).deleteDocument(userDoc1, wikiContext);
        verify(wiki, never()).deleteDocument(userDoc2, wikiContext);
        verify(objectUserClass2).set("active", 0, wikiContext);
    }

    @Test
    void syncUsersDoesNotRemoveWhenAPageFails() throws Exception
    {
        doAnswer(invocation -> {
            FailableConsumer<List<ExternalUser>, Exception> handler = invocation.getArgument(1);
            handler.accept(List.of(new ExternalUser("subject2", false)));
            throw new RuntimeException("Graph request did not return a valid response.");
        }).when(entraIdUsersManager).forEachEntraServerUsersPage(eq(defaultConfiguration), any());

        assertThrows(RuntimeException.class, () -> syncManager.syncUsers(true, true));

        // The users of the missing pages can't be told apart from the removed ones, so nothing is removed.
        verify(wiki, never()).deleteDocument(any(), any());
        // The users disabled before the failure stay disabled, since they are disabled on the Entra ID server.
        verify(objectUserClass2).set("active", 0, wikiContext);
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
        }).when(entraIdUsersManager).forEachEntraServerUsersPage(eq(configuration), any());
    }
}
