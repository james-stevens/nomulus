// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.CSV_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.util.UrlFetchUtils.setPayloadMultipart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPRequest;
import google.registry.testing.AppEngineRule;
import google.registry.testing.InjectRule;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link UrlFetchUtils}. */
@RunWith(JUnit4.class)
public class UrlFetchUtilsTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .build();
  @Rule
  public final InjectRule inject = new InjectRule();

  @Before
  public void setupRandomZeroes() {
    Random random = mock(Random.class);
    inject.setStaticField(UrlFetchUtils.class, "random", random);
    doAnswer(
            info -> {
              Arrays.fill((byte[]) info.getArguments()[0], (byte) 0);
              return null;
            })
        .when(random)
        .nextBytes(any(byte[].class));
  }

  @Test
  public void testSetPayloadMultipart() {
    HTTPRequest request = mock(HTTPRequest.class);
    setPayloadMultipart(
        request, "lol", "cat", CSV_UTF_8, "The nice people at the store say hello. ヘ(◕。◕ヘ)");
    ArgumentCaptor<HTTPHeader> headerCaptor = ArgumentCaptor.forClass(HTTPHeader.class);
    verify(request, times(2)).addHeader(headerCaptor.capture());
    List<HTTPHeader> addedHeaders = headerCaptor.getAllValues();
    assertThat(addedHeaders.get(0).getName()).isEqualTo(CONTENT_TYPE);
    assertThat(addedHeaders.get(0).getValue())
        .isEqualTo(
            "multipart/form-data; "
                + "boundary=\"------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"");
    assertThat(addedHeaders.get(1).getName()).isEqualTo(CONTENT_LENGTH);
    assertThat(addedHeaders.get(1).getValue()).isEqualTo("292");
    String payload = "--------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\r\n"
        + "Content-Disposition: form-data; name=\"lol\"; filename=\"cat\"\r\n"
        + "Content-Type: text/csv; charset=utf-8\r\n"
        + "\r\n"
        + "The nice people at the store say hello. ヘ(◕。◕ヘ)\r\n"
        + "--------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA--";
    verify(request).setPayload(payload.getBytes(UTF_8));
    verifyNoMoreInteractions(request);
  }

  @Test
  public void testSetPayloadMultipart_boundaryInPayload() {
    HTTPRequest request = mock(HTTPRequest.class);
    String payload = "I screamed------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHHH";
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> setPayloadMultipart(request, "lol", "cat", CSV_UTF_8, payload));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Multipart data contains autogenerated boundary: "
                + "------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
  }
}
