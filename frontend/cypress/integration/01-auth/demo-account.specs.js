/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

"use strict";

describe("demo account", () => {
  beforeEach(() => {
    cy.visit("http://localhost:3449/#/auth/login");    
  });

  it.only("create demo account", () => {
    cy.get("a").contains("Create demo account").click()    
    cy.get(".profile").contains("Demo User")
  });

});

