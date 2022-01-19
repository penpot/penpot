/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

"use strict";

describe("recover password", () => {
  beforeEach(() => {
    cy.visit("http://localhost:3449/#/auth/login");
    cy.get("a").contains("Forgot password?").click()
  });

  it("displays the recover form", () => {
    cy.get("input[type=submit]").contains("Recover Password").should("exist");
  });

  it("recover password with wrong mail works", () => {
    cy.get("#email").type("bad@mail.com");
    cy.get("input[type=submit]").contains("Recover Password").click();
    cy.get(".info")
      .should("exist")
      .should("contain", "Password recovery link sent to your inbox.");
  });

  it("recover password with good mail works", () => {
    cy.fixture('validuser.json').then((user) => {
      cy.get("#email").type(user.email);
    });    
    cy.get("input[type=submit]").contains("Recover Password").click();
    cy.get(".info")
      .should("exist")
      .should("contain", "Password recovery link sent to your inbox.");
  });

  it("can go back", () => {
    cy.get("a").contains("Go back").click()
    cy.contains("Great to see you again!").should("exist");
    cy.get("#email").should("exist");
    cy.get("#password").should("exist");
  });
});

