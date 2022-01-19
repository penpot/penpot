/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

"use strict";

describe("account creation", () => {
  let validUser

  beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
      validUser = user;
    });
    cy.visit("http://localhost:3449/#/auth/login");
    cy.get("a").contains("Create an account").click()
  });

  it("displays the account creation form", () => {
    cy.get("input[type=submit]").contains("Create an account").should("exist");
  });

  it("create an account of an existent email fails", () => {
    cy.get("#email").type(validUser.email);
    cy.get("#password").type("anewpassword");
    cy.get("input[type=submit]").contains("Create an account").click();
    cy.get(".error").should("contain", "Email already used")
  });


  it("can go back", () => {
    cy.get("a").contains("Login here").click()
    cy.contains("Great to see you again!").should("exist");
    cy.get("#email").should("exist");
    cy.get("#password").should("exist");
  });
});

