// Copyright 2022 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.request.auth;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature.Header;
import com.google.common.truth.Truth8;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import java.security.GeneralSecurityException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link CookieOAuth2AuthenticationMechanism}. */
@ExtendWith(MockitoExtension.class)
public class CookieOAuth2AuthenticationMechanismTest {

  @RegisterExtension
  public final JpaTestExtensions.JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(User.class).buildUnitTestExtension();

  @Mock private GoogleIdTokenVerifier tokenVerifier;
  @Mock private HttpServletRequest request;

  private GoogleIdToken token;
  private CookieOAuth2AuthenticationMechanism authenticationMechanism;

  @BeforeEach
  void beforeEach() {
    authenticationMechanism = new CookieOAuth2AuthenticationMechanism(tokenVerifier);
    Payload payload = new Payload();
    payload.setEmail("email@email.com");
    payload.setSubject("gaiaId");
    token = new GoogleIdToken(new Header(), payload, new byte[0], new byte[0]);
  }

  @Test
  void testSuccess_validUser() throws Exception {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setGaiaId("gaiaId")
            .setUserRoles(
                new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
            .build();
    insertInDb(user);
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("idToken", "asdf")});
    when(tokenVerifier.verify("asdf")).thenReturn(token);
    AuthResult authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult.isAuthenticated()).isTrue();
    Truth8.assertThat(authResult.userAuthInfo()).isPresent();
    Truth8.assertThat(authResult.userAuthInfo().get().consoleUser()).hasValue(user);
  }

  @Test
  void testFailure_noCookie() {
    when(request.getCookies()).thenReturn(new Cookie[0]);
    assertThat(authenticationMechanism.authenticate(request).isAuthenticated()).isFalse();
  }

  @Test
  void testFailure_badToken() throws Exception {
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("idToken", "asdf")});
    when(tokenVerifier.verify("asdf")).thenReturn(null);
    assertThat(authenticationMechanism.authenticate(request).isAuthenticated()).isFalse();
  }

  @Test
  void testFailure_errorVerifyingToken() throws Exception {
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("idToken", "asdf")});
    when(tokenVerifier.verify("asdf")).thenThrow(new GeneralSecurityException("hi"));
    assertThat(authenticationMechanism.authenticate(request).isAuthenticated()).isFalse();
  }

  @Test
  void testFailure_goodTokenButUnknownUser() throws Exception {
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("idToken", "asdf")});
    when(tokenVerifier.verify("asdf")).thenReturn(token);
    assertThat(authenticationMechanism.authenticate(request).isAuthenticated()).isFalse();
  }
}
