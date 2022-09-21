/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";

describe("recover password", () => {
  beforeEach(() => {
    cy.visit("http://localhost:3449/#/auth/login");
    cy.getBySel("forgot-password").click();
  });

  it("displays the recover form", () => {
    cy.getBySel("recovery-resquest-submit").should("exist");
  });

  it("recover password with wrong mail works", () => {
    cy.get("#email").type("bad@mail.com");
    cy.getBySel("recovery-resquest-submit").click();
    cy.get(".info").should("exist");
  });

  it("recover password with good mail works", () => {
    cy.fixture("validuser.json").then((user) => {
      cy.get("#email").type(user.email);
    });
    cy.getBySel("recovery-resquest-submit").click();
    cy.get(".info").should("exist");
  });

  it("can go back", () => {
    cy.getBySel("go-back-link").click();
    cy.getBySel("login-title").should("exist");
    cy.get("#email").should("exist");
    cy.get("#password").should("exist");
  });
});
