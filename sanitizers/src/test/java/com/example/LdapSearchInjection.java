/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.example.ldap.MockLdapContext;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.LdapContext;

@SuppressWarnings("BanJNDI")
public class LdapSearchInjection {
  private static final LdapContext ctx = new MockLdapContext();

  public static void fuzzerTestOneInput(FuzzedDataProvider fuzzedDataProvider) throws Exception {
    // Externally provided LDAP query input needs to be escaped properly
    String username = fuzzedDataProvider.consumeRemainingAsAsciiString();
    String filter = "(&(uid=" + username + ")(ou=security))";
    ctx.search("dc=example,dc=com", filter, new SearchControls());
  }
}
