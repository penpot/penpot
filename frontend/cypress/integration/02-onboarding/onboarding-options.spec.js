/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

 "use strict";

 describe("onboarding options solo or team", () => {
   beforeEach(() => {
    cy.demoLogin();
    cy.get(".modal-right button").click();    
    cy.get(".onboarding button").click();
    cy.get(".onboarding .skip").click();
   });
 
   it("choose solo option", () => {
    cy.getBySel("onboarding-welcome-title").should("exist");
    cy.getBySel("fly-solo-op").click();
    cy.getBySel("empty-placeholder").should("exist");
   });

   it("choose team option and cancel", () => {
    cy.getBySel("onboarding-welcome-title").should("exist");
    cy.getBySel("team-up-button").click();
    cy.getBySel("onboarding-choice-team-up").should("exist");
    cy.get("button").click();
    cy.getBySel("onboarding-welcome-title").should("exist");
   });

   it("choose team option, set team name and cancel", () => {
    cy.getBySel("onboarding-welcome-title").should("exist");
    cy.getBySel("team-up-button").click();
    cy.getBySel("onboarding-choice-team-up").should("exist");
    cy.get("#name").type("test team");
    cy.get("input[type=submit]").first().click();
    cy.get("#email").should("exist");
    cy.get("button").click();
    cy.getBySel("onboarding-welcome-title").should("exist");
   });

   it("choose team option, set team name and skip", () => {
    cy.getBySel("onboarding-welcome-title").should("exist");
    cy.getBySel("team-up-button").click();
    cy.getBySel("onboarding-choice-team-up").should("exist");
    cy.get("#name").type("test team");
    cy.get("input[type=submit]").first().click();
    cy.get("#email").should("exist");
    cy.get(".skip-action").click();
    cy.getBySel("empty-placeholder").should("exist");
   });

   it("choose team option, set team name and invite", () => {
    cy.getBySel("onboarding-welcome-title").should("exist");
    cy.getBySel("team-up-button").click();
    cy.getBySel("onboarding-choice-team-up").should("exist");
    cy.get("#name").type("test team");
    cy.get("input[type=submit]").first().click();
    cy.get("#email").should("exist");
    cy.get("#email").type("test@test.com");
    cy.get("input[type=submit]").first().click();
    cy.getBySel("empty-placeholder").should("exist");
   });



 });
 
 