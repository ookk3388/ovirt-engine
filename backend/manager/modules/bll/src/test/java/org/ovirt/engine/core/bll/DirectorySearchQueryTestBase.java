package org.ovirt.engine.core.bll;

import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.ovirt.engine.core.utils.MockConfigRule.mockConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.mockito.Mock;
import org.ovirt.engine.core.aaa.AuthenticationProfile;
import org.ovirt.engine.core.aaa.AuthenticationProfileRepository;
import org.ovirt.engine.core.aaa.Authenticator;
import org.ovirt.engine.core.aaa.Directory;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.utils.MockConfigRule;
import org.ovirt.engine.core.utils.RandomUtils;

public abstract class DirectorySearchQueryTestBase {
    @ClassRule
    public static final MockConfigRule mcr = new MockConfigRule(
        mockConfig(ConfigValues.LDAPSecurityAuthentication, "SIMPLE"),
        mockConfig(ConfigValues.SearchResultsLimit, 100),
        mockConfig(ConfigValues.AuthenticationMethod, "LDAP")
    );

    // The name of the authenticator, directory and authentication profile used in the test:
    public static final String NAME = RandomUtils.instance().nextString(10);

    // Mocks for the authentication subsystem:
    @Mock protected Directory directoryMock;
    @Mock protected Authenticator authenticatorMock;
    @Mock protected AuthenticationProfile profileMock;

    @Before
    public void setUp() {
        initMocks(this);

        doReturn(NAME).when(directoryMock).getName();

        doReturn(NAME).when(profileMock).getName();
        doReturn(authenticatorMock).when(profileMock).getAuthenticator();
        doReturn(directoryMock).when(profileMock).getDirectory();
        AuthenticationProfileRepository.getInstance().registerProfile(profileMock);
    }

    @After
    public void tearDown() {
        AuthenticationProfileRepository.getInstance().clear();
    }
}
