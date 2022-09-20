/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

describe("account creation", () => {
  let validUser;

  beforeEach(() => {
    cy.fixture("validuser.json").then((user) => {
      validUser = user;
    });
    cy.visit("http://localhost:3449/#/auth/login");
    cy.getBySel("register-submit").click();
  });

  it("displays the account creation form", () => {
    cy.getBySel("register-form-submit").should("exist");
  });

  it("create an account", () => {
    let email = "mail" +  Date.now() +"@mail.com";
    cy.get("#email").type(email);
    cy.get("#password").type("anewpassword");    
    cy.get("input[type=submit]").click();
    cy.getBySel("register-title").should("exist");
    cy.get("#fullname").type("Test user")
    cy.get("input[type=submit]").click();
    cy.get(".dashboard-layout").should("exist");
  });

  it("create an account of an existent email fails", () => {
    cy.get("#email").type(validUser.email);
    cy.get("#password").type("anewpassword");
    cy.getBySel("register-form-submit").click();
    cy.getBySel("email-input-error").should("exist");
  });

  it("can go back", () => {
    cy.getBySel("login-here-link").click();
    cy.getBySel("login-title").should("exist");
    cy.get("#email").should("exist");
    cy.get("#password").should("exist");
  });
});
