/*
 *  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.iam.identitytrust.core;

import org.eclipse.edc.iam.identitytrust.core.defaults.DefaultDcpParticipantAgentServiceExtension;
import org.eclipse.edc.iam.identitytrust.core.defaults.DefaultTrustedIssuerRegistry;
import org.eclipse.edc.iam.identitytrust.core.defaults.InMemorySignatureSuiteRegistry;
import org.eclipse.edc.iam.identitytrust.core.scope.DcpScopeExtractorRegistry;
import org.eclipse.edc.iam.identitytrust.spi.ClaimTokenCreatorFunction;
import org.eclipse.edc.iam.identitytrust.spi.DcpParticipantAgentServiceExtension;
import org.eclipse.edc.iam.identitytrust.spi.SecureTokenService;
import org.eclipse.edc.iam.identitytrust.spi.scope.ScopeExtractorRegistry;
import org.eclipse.edc.iam.identitytrust.spi.verification.SignatureSuiteRegistry;
import org.eclipse.edc.iam.identitytrust.sts.embedded.EmbeddedSecureTokenService;
import org.eclipse.edc.iam.verifiablecredentials.spi.validation.TrustedIssuerRegistry;
import org.eclipse.edc.jwt.signer.spi.JwsSignerProvider;
import org.eclipse.edc.jwt.validation.jti.JtiValidationStore;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.iam.AudienceResolver;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.token.JwtGenerationService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.eclipse.edc.spi.result.Result.failure;
import static org.eclipse.edc.spi.result.Result.success;

@Extension("Identity And Trust Extension to register default services")
public class DcpDefaultServicesExtension implements ServiceExtension {

    public static final String STS_PUBLIC_KEY_ID = "edc.iam.sts.publickey.id";
    public static final String STS_PRIVATE_KEY_ALIAS = "edc.iam.sts.privatekey.alias";

    public static final String CLAIMTOKEN_VC_KEY = "vc";
    // not a setting, it's defined in Oauth2ServiceExtension
    private static final String OAUTH_TOKENURL_PROPERTY = "edc.oauth.token.url";
    private static final int DEFAULT_STS_TOKEN_EXPIRATION_MIN = 5;
    @Setting(description = "Alias of private key used for signing tokens, retrieved from private key resolver. Required when using EmbeddedSTS", key = STS_PRIVATE_KEY_ALIAS, required = false)
    private String privateKeyAlias;
    @Setting(description = "Key Identifier used by the counterparty to resolve the public key for token validation, e.g. did:example:123#public-key-1. Required when using EmbeddedSTS", key = STS_PUBLIC_KEY_ID, required = false)
    private String publicKeyId;
    @Setting(description = "Self-issued ID Token expiration in minutes. By default is 5 minutes", defaultValue = "" + DEFAULT_STS_TOKEN_EXPIRATION_MIN, key = "edc.iam.sts.token.expiration")
    private long stsTokenExpirationMin;
    @Inject
    private Clock clock;
    @Inject
    private JwsSignerProvider externalSigner;
    @Inject
    private JtiValidationStore jtiValidationStore;

    @Provider(isDefault = true)
    public SecureTokenService createDefaultTokenService(ServiceExtensionContext context) {
        context.getMonitor().info("Using the Embedded STS client, as no other implementation was provided.");

        var errors = new ArrayList<String>();

        checkProperty(STS_PRIVATE_KEY_ALIAS, privateKeyAlias, errors::add);
        checkProperty(STS_PUBLIC_KEY_ID, publicKeyId, errors::add);

        if (!errors.isEmpty()) {
            var msg = String.join(", ", errors);
            throw new EdcException("The following errors were found in the configuration of the Embedded STS: [%s]".formatted(msg));
        }

        if (context.getSetting(OAUTH_TOKENURL_PROPERTY, null) != null) {
            context.getMonitor().warning("The property '%s' was configured, but no remote SecureTokenService was found on the classpath. ".formatted(OAUTH_TOKENURL_PROPERTY) +
                    "This could be an indication of a configuration problem.");
        }

        return new EmbeddedSecureTokenService(new JwtGenerationService(externalSigner), () -> privateKeyAlias, () -> publicKeyId, clock, TimeUnit.MINUTES.toSeconds(stsTokenExpirationMin), jtiValidationStore);
    }

    @Provider(isDefault = true)
    public TrustedIssuerRegistry createInMemoryIssuerRegistry() {
        return new DefaultTrustedIssuerRegistry();
    }

    @Provider(isDefault = true)
    public SignatureSuiteRegistry createSignatureSuiteRegistry() {
        return new InMemorySignatureSuiteRegistry();
    }

    @Provider(isDefault = true)
    public DcpParticipantAgentServiceExtension createDefaultDcpParticipantAgentServiceExtension() {
        return new DefaultDcpParticipantAgentServiceExtension();
    }

    @Provider(isDefault = true)
    public ScopeExtractorRegistry scopeExtractorRegistry() {
        return new DcpScopeExtractorRegistry();
    }

    // Default audience for DCP is the counter-party id
    @Provider(isDefault = true)
    public AudienceResolver defaultAudienceResolver() {
        return (msg) -> Result.success(msg.getCounterPartyId());
    }

    // Default ClaimToken creator function, will use "vc" as key
    @Provider(isDefault = true)
    public ClaimTokenCreatorFunction defaultClaimTokenFunction() {
        return credentials -> {
            if (credentials.isEmpty()) {
                return failure("No VerifiableCredentials were found on VP");
            }
            var b = ClaimToken.Builder.newInstance()
                    .claims(Map.of(CLAIMTOKEN_VC_KEY, credentials));
            return success(b.build());
        };
    }

    private void checkProperty(String key, String value, Consumer<String> onMissing) {
        if (value == null) {
            onMissing.accept("No setting found for key '%s'.".formatted(key));
        }
    }

}