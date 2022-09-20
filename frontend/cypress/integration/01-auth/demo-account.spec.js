/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

describe("demo account", () => {
  beforeEach(() => {
    cy.visit("http://localhost:3449/#/auth/login");
  });

  it("create demo account", () => {
    cy.getBySel("demo-account-link").should("exist");
    cy.getBySel("demo-account-link").click();
    cy.get(".profile").contains("Demo User");
  });
});
