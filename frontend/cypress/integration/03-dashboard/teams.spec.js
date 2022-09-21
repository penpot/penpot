/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

 "use strict";

 import {

    createTeam,
    deleteCurrentTeam
  
  } from '../../support/utils.js';


 describe("teams", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password);
    });
     
   });

   it("can create a new team", () => {
    const teamName = "test team " + Date.now();
    cy.get(".current-team").click();
    cy.getBySel("create-new-team").click();
    cy.get("#name").type(teamName);
    cy.get("input[type=submit]").click();

    cy.get(".current-team").should("contain", teamName);

    //cleanup
    deleteCurrentTeam();

  })

   it("can cancel create a new team", () => {
    cy.get(".current-team").click();
    cy.getBySel("create-new-team").click();
    cy.get(".modal-close-button").click();

    cy.get(".current-team").should("contain", "Your Penpot");
  })

  it("can delete a team", () => {
    const teamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("delete-team").click();
    cy.get(".accept-button").click();
    cy.get(".current-team").should("contain", "Your Penpot");
  })

  it("can cancel the deletion of a team", () => {
    const teamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("delete-team").click();
    cy.get(".cancel-button").click();
    cy.get(".current-team").should("contain", teamName);


    //cleanup
    deleteCurrentTeam();
  })

  it("can see the members page of a team", () => {
    const teamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("team-members").click();

    cy.get(".dashboard-title").should("contain", "Members");
    cy.fixture('validuser.json').then((user) => {
        cy.get(".dashboard-table").should("contain", user.email);
    });

    //cleanup
    deleteCurrentTeam();
  })

  it("can invite someone to a team", () => {
    const teamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("team-members").click();

    cy.getBySel("invite-member").click();
    cy.get("#email").type("mail@mail.com");
    cy.get(".custom-select select").select("admin");
    cy.get("input[type=submit]").click();

    cy.get(".success").should("exist");

    //cleanup
    deleteCurrentTeam();
  })

  it("can see the settings page of a team", () => {
    const teamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("team-settings").click();

    cy.get(".dashboard-title").should("contain", "Settings");
    
    cy.get(".team-settings .name").should("contain", teamName);
    
    //cleanup
    deleteCurrentTeam();
  })

  it("can rename team", () => {
    const teamName = "test team " + Date.now();
    const newTeamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("rename-team").click();
    cy.get("#name").type(newTeamName);
    cy.get("input[type=submit]").click();

    cy.get(".current-team").should("contain", newTeamName);
    
    //cleanup
    deleteCurrentTeam();
  })

  it("can cancel the rename of a team", () => {
    const teamName = "test team " + Date.now();
    createTeam(teamName);

    cy.get(".icon-actions").first().click();
    cy.getBySel("rename-team").click();
    cy.get(".modal-close-button").click();

    cy.get(".current-team").should("contain", teamName);
    
    //cleanup
    deleteCurrentTeam();
  })


  

})