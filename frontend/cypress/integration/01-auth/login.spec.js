/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

describe("login", () => {
  beforeEach(() => {
    cy.visit("http://localhost:3449/#/auth/login");
  });

  it("displays the login form", () => {
    cy.getBySel("login-title").should("exist");
    cy.get("#email").should("exist");
    cy.get("#password").should("exist");
  });

  it("can't login with an invalid user", () => {
    cy.get("#email").type("bad@mail.com");
    cy.get("#password").type("badpassword");
    cy.getBySel("login-submit").click();
    cy.getBySel("login-banner").should("exist");
  });

  it("can login with a valid user", () => {
    cy.fixture("validuser.json").then((user) => {
      cy.get("#email").type(user.email);
      cy.get("#password").type(user.password);
    });

    cy.getBySel("login-submit").click();
    cy.get(".dashboard-layout").should("exist");
  });
});
