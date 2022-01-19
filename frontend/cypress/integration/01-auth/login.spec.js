/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

"use strict";

describe("login", () => {
  beforeEach(() => {
    cy.visit("http://localhost:3449/#/auth/login");
  });

  it("displays the login form", () => {
    cy.contains("Great to see you again!").should("exist");
    cy.get("#email").should("exist");
    cy.get("#password").should("exist");
  });

  it("can't login with an invalid user", () => {
    cy.get("#email").type("bad@mail.com");
    cy.get("#password").type("badpassword");
    cy.get("input[type=submit]").first().click();
    cy.get(".warning")
      .should("exist")
      .should("contain", "Username or password seems to be wrong.");
  });

  it("can login with a valid user", () => {
    cy.fixture('validuser.json').then((user) => {
      cy.get("#email").type(user.email);
      cy.get("#password").type(user.password);
    });
    
    cy.get("input[type=submit]").first().click();
    cy.get(".dashboard-layout").should("exist");
  });
});

