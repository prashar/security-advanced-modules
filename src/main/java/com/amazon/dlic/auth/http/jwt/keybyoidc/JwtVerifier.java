/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License").
 *  You may not use this file except in compliance with the License.
 *  A copy of the License is located at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.amazon.dlic.auth.http.jwt.keybyoidc;

import org.apache.cxf.rs.security.jose.jwk.JsonWebKey;
import org.apache.cxf.rs.security.jose.jwk.KeyType;
import org.apache.cxf.rs.security.jose.jwk.PublicKeyUse;
import org.apache.cxf.rs.security.jose.jws.JwsJwtCompactConsumer;
import org.apache.cxf.rs.security.jose.jws.JwsSignatureVerifier;
import org.apache.cxf.rs.security.jose.jws.JwsUtils;
import org.apache.cxf.rs.security.jose.jwt.JwtClaims;
import org.apache.cxf.rs.security.jose.jwt.JwtException;
import org.apache.cxf.rs.security.jose.jwt.JwtToken;
import org.apache.cxf.rs.security.jose.jwt.JwtUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.base.Strings;

public class JwtVerifier {

	private final static Logger log = LogManager.getLogger(JwtVerifier.class);

	private final KeyProvider keyProvider;

	public JwtVerifier(KeyProvider keyProvider) {
		this.keyProvider = keyProvider;
	}

	public JwtToken getVerifiedJwtToken(String encodedJwt) throws BadCredentialsException {
		try {
			JwsJwtCompactConsumer jwtConsumer = new JwsJwtCompactConsumer(encodedJwt);
			JwtToken jwt = jwtConsumer.getJwtToken();

			String escapedKid = jwt.getJwsHeaders().getKeyId();
			String kid = escapedKid;
			if (!kid.isEmpty()) {
				kid = StringEscapeUtils.unescapeJava(escapedKid);
				if (escapedKid != kid) {
					log.info("Escaped Key ID from JWT Token");
				}
			}
			JsonWebKey key = keyProvider.getKey(kid);
			
			// Algorithm is not mandatory for the key material, so we set it to the same as the JWT
			if (key.getAlgorithm() == null && key.getPublicKeyUse() == PublicKeyUse.SIGN && key.getKeyType() == KeyType.RSA)
			{
				key.setAlgorithm(jwt.getJwsHeaders().getAlgorithm());
			}
			
			JwsSignatureVerifier signatureVerifier = getInitializedSignatureVerifier(key);

			boolean signatureValid = jwtConsumer.verifySignatureWith(signatureVerifier);

			if (!signatureValid && Strings.isNullOrEmpty(kid)) {
				key = keyProvider.getKeyAfterRefresh(null);
				signatureVerifier = getInitializedSignatureVerifier(key);
				signatureValid = jwtConsumer.verifySignatureWith(signatureVerifier);
			}

			if (!signatureValid) {
				throw new BadCredentialsException("Invalid JWT signature");
			}

			validateClaims(jwt);

			return jwt;
		} catch (JwtException e) {
			throw new BadCredentialsException(e.getMessage(), e);
		}
	}

	private JwsSignatureVerifier getInitializedSignatureVerifier(JsonWebKey key)
			throws BadCredentialsException, JwtException {
		JwsSignatureVerifier result = JwsUtils.getSignatureVerifier(key);

		if (result == null) {
			throw new BadCredentialsException("Cannot verify JWT");
		} else {
			return result;
		}
	}

	private void validateClaims(JwtToken jwt) throws BadCredentialsException, JwtException {
		JwtClaims claims = jwt.getClaims();

		if (claims != null) {
			JwtUtils.validateJwtExpiry(claims, 0, false);
			JwtUtils.validateJwtNotBefore(claims, 0, false);
		}
	}
}
