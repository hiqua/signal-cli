/*
  Copyright (C) 2015-2021 AsamK and contributors

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.asamk.signal.manager;

import org.asamk.signal.manager.api.CaptchaRequiredException;
import org.asamk.signal.manager.api.IncorrectPinException;
import org.asamk.signal.manager.api.PinLockedException;
import org.asamk.signal.manager.config.ServiceConfig;
import org.asamk.signal.manager.config.ServiceEnvironmentConfig;
import org.asamk.signal.manager.helper.PinHelper;
import org.asamk.signal.manager.storage.SignalAccount;
import org.asamk.signal.manager.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.KbsPinData;
import org.whispersystems.signalservice.api.KeyBackupServicePinException;
import org.whispersystems.signalservice.api.KeyBackupSystemNoDataException;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.kbs.MasterKey;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.push.LockedException;
import org.whispersystems.signalservice.internal.push.RequestVerificationCodeResponse;
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse;
import org.whispersystems.signalservice.internal.util.DynamicCredentialsProvider;

import java.io.IOException;
import java.util.function.Consumer;

import static org.asamk.signal.manager.config.ServiceConfig.capabilities;

public class RegistrationManagerImpl implements RegistrationManager {

    private final static Logger logger = LoggerFactory.getLogger(RegistrationManagerImpl.class);

    private SignalAccount account;
    private final PathConfig pathConfig;
    private final ServiceEnvironmentConfig serviceEnvironmentConfig;
    private final String userAgent;
    private final Consumer<Manager> newManagerListener;

    private final SignalServiceAccountManager accountManager;
    private final PinHelper pinHelper;

    RegistrationManagerImpl(
            SignalAccount account,
            PathConfig pathConfig,
            ServiceEnvironmentConfig serviceEnvironmentConfig,
            String userAgent,
            Consumer<Manager> newManagerListener
    ) {
        this.account = account;
        this.pathConfig = pathConfig;
        this.serviceEnvironmentConfig = serviceEnvironmentConfig;
        this.userAgent = userAgent;
        this.newManagerListener = newManagerListener;

        GroupsV2Operations groupsV2Operations;
        try {
            groupsV2Operations = new GroupsV2Operations(ClientZkOperations.create(serviceEnvironmentConfig.getSignalServiceConfiguration()));
        } catch (Throwable ignored) {
            groupsV2Operations = null;
        }
        this.accountManager = new SignalServiceAccountManager(serviceEnvironmentConfig.getSignalServiceConfiguration(),
                new DynamicCredentialsProvider(
                        // Using empty UUID, because registering doesn't work otherwise
                        null, account.getAccount(), account.getPassword(), SignalServiceAddress.DEFAULT_DEVICE_ID),
                userAgent,
                groupsV2Operations,
                ServiceConfig.AUTOMATIC_NETWORK_RETRY);
        final var keyBackupService = accountManager.getKeyBackupService(ServiceConfig.getIasKeyStore(),
                serviceEnvironmentConfig.getKeyBackupConfig().getEnclaveName(),
                serviceEnvironmentConfig.getKeyBackupConfig().getServiceId(),
                serviceEnvironmentConfig.getKeyBackupConfig().getMrenclave(),
                10);
        this.pinHelper = new PinHelper(keyBackupService);
    }

    @Override
    public void register(boolean voiceVerification, String captcha) throws IOException, CaptchaRequiredException {
        captcha = captcha == null ? null : captcha.replace("signalcaptcha://", "");
        if (account.getAci() != null) {
            try {
                final var accountManager = new SignalServiceAccountManager(serviceEnvironmentConfig.getSignalServiceConfiguration(),
                        new DynamicCredentialsProvider(account.getAci(),
                                account.getAccount(),
                                account.getPassword(),
                                account.getDeviceId()),
                        userAgent,
                        null,
                        ServiceConfig.AUTOMATIC_NETWORK_RETRY);
                accountManager.setAccountAttributes(account.getEncryptedDeviceName(),
                        null,
                        account.getLocalRegistrationId(),
                        true,
                        null,
                        account.getPinMasterKey() == null ? null : account.getPinMasterKey().deriveRegistrationLock(),
                        account.getSelfUnidentifiedAccessKey(),
                        account.isUnrestrictedUnidentifiedAccess(),
                        capabilities,
                        account.isDiscoverableByPhoneNumber());
                account.setRegistered(true);
                logger.info("Reactivated existing account, verify is not necessary.");
                if (newManagerListener != null) {
                    final var m = new ManagerImpl(account, pathConfig, serviceEnvironmentConfig, userAgent);
                    account = null;
                    newManagerListener.accept(m);
                }
                return;
            } catch (IOException e) {
                logger.debug("Failed to reactivate account");
            }
        }
        final ServiceResponse<RequestVerificationCodeResponse> response;
        if (voiceVerification) {
            response = accountManager.requestVoiceVerificationCode(Utils.getDefaultLocale(),
                    Optional.fromNullable(captcha),
                    Optional.absent(),
                    Optional.absent());
        } else {
            response = accountManager.requestSmsVerificationCode(false,
                    Optional.fromNullable(captcha),
                    Optional.absent(),
                    Optional.absent());
        }
        try {
            handleResponseException(response);
        } catch (org.whispersystems.signalservice.api.push.exceptions.CaptchaRequiredException e) {
            throw new CaptchaRequiredException(e.getMessage(), e);
        }
    }

    @Override
    public void verifyAccount(
            String verificationCode, String pin
    ) throws IOException, PinLockedException, IncorrectPinException {
        verificationCode = verificationCode.replace("-", "");
        VerifyAccountResponse response;
        MasterKey masterKey;
        try {
            response = verifyAccountWithCode(verificationCode, null);

            masterKey = null;
            pin = null;
        } catch (LockedException e) {
            if (pin == null) {
                throw new PinLockedException(e.getTimeRemaining());
            }

            KbsPinData registrationLockData;
            try {
                registrationLockData = pinHelper.getRegistrationLockData(pin, e);
            } catch (KeyBackupSystemNoDataException ex) {
                throw new IOException(e);
            } catch (KeyBackupServicePinException ex) {
                throw new IncorrectPinException(ex.getTriesRemaining());
            }
            if (registrationLockData == null) {
                throw e;
            }

            var registrationLock = registrationLockData.getMasterKey().deriveRegistrationLock();
            try {
                response = verifyAccountWithCode(verificationCode, registrationLock);
            } catch (LockedException _e) {
                throw new AssertionError("KBS Pin appeared to matched but reg lock still failed!");
            }
            masterKey = registrationLockData.getMasterKey();
        }

        //accountManager.setGcmId(Optional.of(GoogleCloudMessaging.getInstance(this).register(REGISTRATION_ID)));
        account.finishRegistration(ACI.parseOrNull(response.getUuid()), masterKey, pin);

        ManagerImpl m = null;
        try {
            m = new ManagerImpl(account, pathConfig, serviceEnvironmentConfig, userAgent);
            account = null;

            m.refreshPreKeys();
            if (response.isStorageCapable()) {
                m.retrieveRemoteStorage();
            }
            // Set an initial empty profile so user can be added to groups
            try {
                m.setProfile(null, null, null, null, null);
            } catch (NoClassDefFoundError e) {
                logger.warn("Failed to set default profile: {}", e.getMessage());
            }

            if (newManagerListener != null) {
                newManagerListener.accept(m);
                m = null;
            }
        } finally {
            if (m != null) {
                m.close();
            }
        }
    }

    private VerifyAccountResponse verifyAccountWithCode(
            final String verificationCode, final String registrationLock
    ) throws IOException {
        final ServiceResponse<VerifyAccountResponse> response;
        if (registrationLock == null) {
            response = accountManager.verifyAccount(verificationCode,
                    account.getLocalRegistrationId(),
                    true,
                    account.getSelfUnidentifiedAccessKey(),
                    account.isUnrestrictedUnidentifiedAccess(),
                    ServiceConfig.capabilities,
                    account.isDiscoverableByPhoneNumber());
        } else {
            response = accountManager.verifyAccountWithRegistrationLockPin(verificationCode,
                    account.getLocalRegistrationId(),
                    true,
                    registrationLock,
                    account.getSelfUnidentifiedAccessKey(),
                    account.isUnrestrictedUnidentifiedAccess(),
                    ServiceConfig.capabilities,
                    account.isDiscoverableByPhoneNumber());
        }
        handleResponseException(response);
        return response.getResult().get();
    }

    @Override
    public void close() {
        if (account != null) {
            account.close();
            account = null;
        }
    }

    private void handleResponseException(final ServiceResponse<?> response) throws IOException {
        final var throwableOptional = response.getExecutionError().or(response.getApplicationError());
        if (throwableOptional.isPresent()) {
            if (throwableOptional.get() instanceof IOException) {
                throw (IOException) throwableOptional.get();
            } else {
                throw new IOException(throwableOptional.get());
            }
        }
    }
}
