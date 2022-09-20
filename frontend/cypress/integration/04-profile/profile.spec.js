/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
"use strict";

describe("profile", () => {
  beforeEach(() => {
    cy.fixture("validuser.json").then((user) => {
      cy.login(user.email, user.password);
    });
  });

  it("open profile section", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").should("exist");
    cy.getBySel("profile-profile-opt").click();
    cy.getBySel("account-title").should("exist");
  });

  it("change profile name", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.get("#fullname").should("exist");
    cy.get("#fullname").clear().type("New name").type("{enter}");
    cy.get(".banner.success").should("exist");
  });

  it("change profile image with png", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.getBySel("profile-image-input").should("exist");

    cy.get(".profile img").then((oldImg) => {
      cy.getBySel("profile-image-input").attachFile("test-image-png.png");
      cy.get(".profile img")
        .invoke("attr", "src")
        .should("not.eq", oldImg[0].src);
    });
  });

  it("change profile image with jpg", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.getBySel("profile-image-input").should("exist");

    cy.get(".profile img").then((oldImg) => {
      cy.getBySel("profile-image-input").attachFile("test-image-jpg.jpg");
      cy.get(".profile img")
        .invoke("attr", "src")
        .should("not.eq", oldImg[0].src);
    });
  });

  it("change profile email", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.get(".change-email").should("exist");
    cy.get(".change-email").click();
    cy.getBySel("change-email-title").should("exist");
    cy.fixture("validuser.json").then((user) => {
      cy.get("#email-1").type(user.email);
      cy.get("#email-2").type(user.email);
    });
    cy.getBySel("change-email-submit").click();
    cy.get(".banner.info").should("exist");
  });

  it("type wrong email while trying to update should throw an error", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.get(".change-email").click();
    cy.fixture("validuser.json").then((user) => {
      cy.get("#email-1").type(user.email);
    });
    cy.get("#email-2").type("bad@email.com");
    cy.getBySel("change-email-submit").click();
    cy.get(".error").should("exist");
  });

  it("open password section", () => {
    cy.get(".profile").click();
    cy.getBySel("password-profile-opt").click();
    cy.get(".password-form").should("exist");
  });

  it("type old password wrong should throw an error", () => {
    cy.get(".profile").click();
    cy.getBySel("password-profile-opt").click();
    cy.get("#password-old").type("badpassword");
    cy.get("#password-1").type("pretty-new-password");
    cy.get("#password-2").type("pretty-new-password");
    cy.getBySel("submit-password").click();
    cy.get(".error").should("exist");
  });

  it("type same old password should work", () => {
    cy.get(".profile").click();
    cy.getBySel("password-profile-opt").click();
    cy.fixture("validuser.json").then((user) => {
      cy.get("#password-old").type(user.password);
      cy.get("#password-1").type(user.password);
      cy.get("#password-2").type(user.password);
    });
    cy.getBySel("submit-password").click();
    cy.get(".banner.success").should("exist");
  });

  it("open settings section", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.getBySel("settings-profile").should("exist");
  });

  it("set lang to Spanish and back to english", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.getBySel("settings-profile").click();
    cy.getBySel("setting-lang").should("exist");
    cy.getBySel("setting-lang").select("es");
    cy.getBySel("submit-lang-change").should("exist");
    cy.getBySel("submit-lang-change").click();
    cy.contains("Tu cuenta").should("exist");
    cy.getBySel("setting-lang").select("en");
    cy.getBySel("submit-lang-change").click();
    cy.contains("Your account").should("exist");
  });

  it("log out from app", () => {
    cy.get(".profile").click();
    cy.getBySel("logout-profile-opt").should("exist");
    cy.getBySel("logout-profile-opt").click();
    cy.getBySel("login-title").should("exist");
  });
});

describe("remove account", () => {
  it("create demo account and delete it", () => {
    cy.visit("http://localhost:3449/#/auth/login");
    cy.getBySel("demo-account-link").click();
    cy.getBySel("onboarding-next-btn").click();
    cy.getBySel("opsource-next-btn").click();
    cy.getBySel("skip-btn").click();
    cy.getBySel("fly-solo-op").click();
    cy.getBySel("close-templates-btn").click();
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.getBySel("remove-acount-btn").click();
    cy.getBySel("delete-account-btn").click();
    cy.getBySel("login-title").should("exist");
  });
});
