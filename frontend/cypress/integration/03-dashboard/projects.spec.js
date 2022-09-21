/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

 "use strict";

 import {

  createProject,
  deleteFirstProject

} from '../../support/utils.js';



 describe("projects", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password)
    });

   });

   it("displays the projects page", () => {
     cy.get(".dashboard-title").should("contain", "Projects");
   });

   it("can create a new project", () => {
    let projectName = "test project " + Date.now();
    cy.get(".project").then((projects) => {
      cy.getBySel("new-project-button").click();
      cy.get('.project').should('have.length', projects.length + 1);
      cy.get('.project').first().find(".edit-wrapper").type(projectName + "{enter}")
      cy.get('.project').first().find("h2").should("contain", projectName);

      //cleanup: delete project
      deleteFirstProject();
    })

  })

  it("can rename a project", () => {
      let projectName = "test project " + Date.now();
      let projectName2 = "renamed project " + Date.now();

      createProject(projectName);

      cy.get('.project').first().find("h2").should("contain", projectName);
      cy.get('.project').first().find("[data-test=project-options]").click();
      cy.get('.project').first().find("[data-test=project-rename]").click();
      cy.get('.project').first().find(".edit-wrapper").type(projectName2 + "{enter}")
      cy.get('.project').first().find("h2").should("contain", projectName2)

      //cleanup: delete project
      deleteFirstProject();
  });

  it("can delete a project", () => {
    createProject();
    cy.get(".project").then((projects) => {
      cy.get('.project').first().find("[data-test=project-options]").click();
      cy.wait(500);
      cy.getBySel("project-delete").click();
      cy.wait(500);
      cy.get('.accept-button').click();
      cy.wait(500);

      cy.get('.project').should('have.length', projects.length - 1);
    })
  });

  it("can cancel the deletion of a project", () => {
    createProject();
    cy.get(".project").then((projects) => {
      cy.get('.project').first().find("[data-test=project-options]").click();
      cy.wait(500);
      cy.getBySel("project-delete").click();
      cy.wait(500);
      cy.get('.cancel-button').click();
      cy.wait(500);

      cy.get('.project').should('have.length', projects.length);


      //cleanup: delete project
      deleteFirstProject();
    })
  });

  it("can duplicate a project", () => {
    let projectName = "test project " + Date.now();
    createProject(projectName);
    cy.get('.project').first().find("[data-test=project-options]").click();
    cy.wait(500);
    cy.getBySel("project-duplicate").click();
    cy.getBySel("project-title").should("exist");
    cy.getBySel("project-title").should("contain", projectName+" (");


    //cleanup: delete project
    cy.get(".recent-projects").click();
    deleteFirstProject();
    deleteFirstProject();
  });


  it("can move a project to a team", () => {
    let projectName = "test project " + Date.now();
    createProject(projectName);

    cy.fixture('validuser.json').then((user) => {
      cy.get('.project').first().find("[data-test=project-options]").click();
      cy.get('.project').first().find("[data-test=project-move-to]").click();
      cy.get('a').contains(user.team).click();

      cy.get(".current-team").should("contain", user.team);
      cy.get(".project").first().should("contain", projectName);


      //cleanup: delete project
      deleteFirstProject();
    });
  });


  it("pin and unpin project to sidebar", () => {
    let projectName = "test project " + Date.now();
    createProject(projectName);

    cy.get(".project").first().find(".icon-pin-fill").should("exist");
    cy.getBySel("pinned-projects").should("contain", projectName);

    //unpin
    cy.get(".project").first().find(".pin-icon").click();
    cy.get(".project").first().find(".icon-pin-fill").should("not.exist");
    cy.getBySel("pinned-projects").should("not.contain", projectName);

    //pin
    cy.get(".project").first().find(".pin-icon").click();
    cy.get(".project").first().find(".icon-pin-fill").should("exist");
    cy.getBySel("pinned-projects").should("contain", projectName);

    //cleanup: delete project
    deleteFirstProject();
  });

 });


