/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.impl;

import com.fasterxml.jackson.databind.InjectableValues.Std;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.impl.systemfield.SystemField;
import org.apache.druid.data.input.impl.systemfield.SystemFields;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.metadata.DefaultPasswordProvider;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public class HttpInputSourceTest
{
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testSerde() throws IOException
  {
    HttpInputSourceConfig httpInputSourceConfig = new HttpInputSourceConfig(null, null);
    final ObjectMapper mapper = new ObjectMapper();
    mapper.setInjectableValues(new Std().addValue(HttpInputSourceConfig.class, httpInputSourceConfig));
    final HttpInputSource source = new HttpInputSource(
        ImmutableList.of(URI.create("http://test.com/http-test")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        new SystemFields(EnumSet.of(SystemField.URI)),
        null,
        httpInputSourceConfig
    );
    final byte[] json = mapper.writeValueAsBytes(source);
    final HttpInputSource fromJson = (HttpInputSource) mapper.readValue(json, InputSource.class);
    Assert.assertEquals(source, fromJson);
  }

  @Test
  public void testConstructorAllowsOnlyDefaultProtocols()
  {
    new HttpInputSource(
        ImmutableList.of(URI.create("http:///")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        null,
        null,
        new HttpInputSourceConfig(null, null)
    );

    new HttpInputSource(
        ImmutableList.of(URI.create("https:///")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        null,
        null,
        new HttpInputSourceConfig(null, null)
    );

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Only [http, https] protocols are allowed");
    new HttpInputSource(
        ImmutableList.of(URI.create("my-protocol:///")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        null,
        null,
        new HttpInputSourceConfig(null, null)
    );
  }

  @Test
  public void testConstructorAllowsOnlyCustomProtocols()
  {
    final HttpInputSourceConfig customConfig = new HttpInputSourceConfig(ImmutableSet.of("druid"), null);
    new HttpInputSource(
        ImmutableList.of(URI.create("druid:///")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        null,
        null,
        customConfig
    );

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Only [druid] protocols are allowed");
    new HttpInputSource(
        ImmutableList.of(URI.create("https:///")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        null,
        null,
        customConfig
    );
  }

  @Test
  public void testSystemFields()
  {
    HttpInputSourceConfig httpInputSourceConfig = new HttpInputSourceConfig(null, null);
    final HttpInputSource inputSource = new HttpInputSource(
        ImmutableList.of(URI.create("http://test.com/http-test")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        new SystemFields(EnumSet.of(SystemField.URI, SystemField.PATH)),
        null,
        httpInputSourceConfig
    );

    Assert.assertEquals(
        EnumSet.of(SystemField.URI, SystemField.PATH),
        inputSource.getConfiguredSystemFields()
    );

    final HttpEntity entity = new HttpEntity(URI.create("https://example.com/foo"), null, null, null);

    Assert.assertEquals("https://example.com/foo", inputSource.getSystemFieldValue(entity, SystemField.URI));
    Assert.assertEquals("/foo", inputSource.getSystemFieldValue(entity, SystemField.PATH));
    Assert.assertEquals(inputSource.getRequestHeaders(), Collections.emptyMap());
  }

  @Test
  public void testAllowedHeaders()
  {
    HttpInputSourceConfig httpInputSourceConfig = new HttpInputSourceConfig(
        null,
        Sets.newHashSet("R-cookie", "Content-type")
    );
    final HttpInputSource inputSource = new HttpInputSource(
        ImmutableList.of(URI.create("http://test.com/http-test")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        new SystemFields(EnumSet.of(SystemField.URI, SystemField.PATH)),
        ImmutableMap.of("r-Cookie", "test", "Content-Type", "application/json"),
        httpInputSourceConfig
    );
    Set<String> expectedSet = inputSource.getRequestHeaders()
                                         .keySet()
                                         .stream()
                                         .map(StringUtils::toLowerCase)
                                         .collect(Collectors.toSet());
    Assert.assertEquals(expectedSet, httpInputSourceConfig.getAllowedHeaders());
  }

  @Test
  public void shouldFailOnForbiddenHeaders()
  {
    HttpInputSourceConfig httpInputSourceConfig = new HttpInputSourceConfig(
        null,
        Sets.newHashSet("R-cookie", "Content-type")
    );
    expectedException.expect(DruidException.class);
    expectedException.expectMessage(
        "Got forbidden header G-Cookie, allowed headers are only [r-cookie, content-type]");
    new HttpInputSource(
        ImmutableList.of(URI.create("http://test.com/http-test")),
        "myName",
        new DefaultPasswordProvider("myPassword"),
        new SystemFields(EnumSet.of(SystemField.URI, SystemField.PATH)),
        ImmutableMap.of("G-Cookie", "test", "Content-Type", "application/json"),
        httpInputSourceConfig
    );
  }

  @Test
  public void testEquals()
  {
    EqualsVerifier.forClass(HttpInputSource.class)
                  .usingGetClass()
                  .verify();
  }
}
