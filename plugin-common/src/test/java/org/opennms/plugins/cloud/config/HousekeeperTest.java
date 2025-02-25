/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2022 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2022 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.plugins.cloud.config;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opennms.plugins.cloud.config.ConfigStore.Key.configstatus;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.runtime.Container;
import org.opennms.integration.api.v1.runtime.RuntimeInfo;

public class HousekeeperTest {

    private Housekeeper hk;
    private ConfigurationManager cm;
    private ConfigStore config;
    private RuntimeInfo runtimeInfo;

    @Before
    public void setUp() {
        config = mock(ConfigStore.class);
        runtimeInfo = mock(RuntimeInfo.class);
        cm = mock(ConfigurationManager.class);
        when(config.getOrNull(configstatus)).thenReturn(ConfigurationManager.ConfigStatus.CONFIGURED.name());
    }

    @Test
    public void shouldRenewConfigForExpiredTokenOpenNms() {
        when(runtimeInfo.getContainer()).thenReturn(Container.OPENNMS);
        hk = new Housekeeper(cm, config, runtimeInfo, 1, 1, 1);
        doReturn(Instant.now().plusSeconds(60 * 60)) // first time: token valid
                .doReturn(Instant.now()) // second time: token expired
                .when(cm).getTokenExpiration();
        doReturn(Instant.now().plusSeconds(60 * 60)).when(cm).getCertExpiration(); // cert always valid
        hk.init();
        verify(cm, times(0)).configure();
        await()
                .during(Duration.ofMillis(800)) // no config should be called during ramp up time (1sec)
                .atMost(Duration.ofMillis(5000)) // config should have been called by now
                .until(() -> mockingDetails(cm).getInvocations().stream().anyMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, times(2)).configure(); // called by token and cert renewal
    }

    @Test
    public void shouldRenewExpiredCertsOpenNms() throws CertificateException {
        when(runtimeInfo.getContainer()).thenReturn(Container.OPENNMS);
        hk = new Housekeeper(cm, config, runtimeInfo, 1, 1, 1);
        doReturn(Instant.now().plusSeconds(60 * 60)) // first time: cert valid
                .doReturn(Instant.now()) // second time: cert expired
                .when(cm).getCertExpiration();
        doReturn(Instant.now().plusSeconds(60 * 60)).when(cm).getTokenExpiration(); // token always valid
        hk.init();
        verify(cm, times(0)).renewCerts();
        verify(cm, times(0)).configure();
        await()
                .during(Duration.ofMillis(800)) // no cert renewal should be called during ramp up time (1sec)
                .atMost(Duration.ofMillis(5000)) // cert renewal should have been called by now
                .until(() -> mockingDetails(cm).getInvocations().stream().anyMatch(i -> "renewCerts".equals(i.getMethod().getName())));
        verify(cm, times(1)).renewCerts();
        verify(cm, times(2)).configure(); // called by token and cert renewal
    }

    @Test
    public void shouldNotCrashOnExceptionOpenNms() throws CertificateException {
        when(runtimeInfo.getContainer()).thenReturn(Container.OPENNMS);
        hk = new Housekeeper(cm, config, runtimeInfo, 1, 1000, 1);
        doReturn(Instant.now()).when(cm).getTokenExpiration(); // trigger every time
        doReturn(Instant.now().plusSeconds(60 * 60)).when(cm).getCertExpiration(); // cert always valid
        doThrow(new NullPointerException("oh oh")).when(cm).configure(); // Exception should be ignored
        hk.init();
        verify(cm, times(0)).configure();
        await()
                .during(Duration.ofMillis(800)) // no config should be called during ramp up time (1sec)
                .atMost(Duration.ofMillis(5000)) // config should have been called by now
                .until(() -> mockingDetails(cm).getInvocations().stream().anyMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, times(2)).configure(); // token + cert
        clearInvocations(cm);
        await()
                .during(Duration.ofMillis(800)) // no config should be called during ramp up time (1sec)
                .atMost(Duration.ofMillis(5000)) // config should have been called by now
                .until(() -> mockingDetails(cm).getInvocations().stream().anyMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, times(1)).configure();
    }

    @Test
    public void shouldReconfigureIfConfigChanged_Sentinel() {
        when(runtimeInfo.getContainer()).thenReturn(Container.SENTINEL);
        hk = new Housekeeper(cm, config, runtimeInfo, 1, 1, 1);
        hk.init();

        // wait for a bit to check if configure was called. It shouldn't since the token hasn't changed
        await()
                .during(Duration.ofMillis(2000))
                .atMost(Duration.ofMillis(3000))
                .until(() -> mockingDetails(cm).getInvocations().stream().noneMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, never()).configure();

        // change token and see if configure has been called
        doReturn("someNewHost").when(config).getOrNull(ConfigStore.Key.grpchost); // change config
        await()
                .during(Duration.ofMillis(2000))
                .atMost(Duration.ofMillis(3000))
                .until(() -> mockingDetails(cm).getInvocations().stream().anyMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, times(1)).configure();
        clearInvocations(cm);

        // wait for a bit again to check if configure was called. It shouldn't since the token hasn't changed
        await()
                .during(Duration.ofMillis(2000))
                .atMost(Duration.ofMillis(3000))
                .until(() -> mockingDetails(cm).getInvocations().stream().noneMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, never()).configure();
        clearInvocations(cm);

        // wait for a bit again to check if configure was called. It shouldn't since the token hasn't changed
        await()
                .during(Duration.ofMillis(2000))
                .atMost(Duration.ofMillis(3000))
                .until(() -> mockingDetails(cm).getInvocations().stream().noneMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, never()).configure();
    }

    @Test
    public void shouldTriggerNothingIfNotInitialized_OpenNms() throws CertificateException {
        when(cm.getStatus()).thenReturn(ConfigurationManager.ConfigStatus.NOT_ATTEMPTED);
        shouldTriggerNothing(Container.OPENNMS);
    }

    @Test
    public void shouldTriggerNothingIfNotInitialized_Sentinel() throws CertificateException {
        shouldTriggerNothing(Container.SENTINEL);
    }

    @Test
    public void shouldTriggerNothingIfUnsupportedContainer() throws CertificateException {
        when(cm.getStatus()).thenReturn(ConfigurationManager.ConfigStatus.CONFIGURED);
        shouldTriggerNothing(Container.MINION);
        shouldTriggerNothing(Container.OTHER);
    }

    private void shouldTriggerNothing(final Container container) throws CertificateException {
        when(runtimeInfo.getContainer()).thenReturn(container);
        hk = new Housekeeper(cm, config, runtimeInfo, 1, 1, 1);
        hk.init();

        // wait for a bit to check if configure was called. It shouldn't since the token hasn't changed
        await()
                .during(Duration.ofMillis(2000))
                .atMost(Duration.ofMillis(3000))
                .until(() -> mockingDetails(cm).getInvocations().stream().noneMatch(i -> "configure".equals(i.getMethod().getName())));
        verify(cm, never()).configure();
        verify(cm, never()).renewCerts();
    }

    @After
    public void tearDown() {
        hk.destroy();
    }

}