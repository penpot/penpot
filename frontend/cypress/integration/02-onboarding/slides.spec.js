/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

 "use strict";

 describe("onboarding slides", () => {
   beforeEach(() => {
    cy.demoLogin();
     
   });
 
   it("go trough all the onboarding slides", () => {
     cy.get(".modal-right").should("contain", "Welcome to Penpot");
     cy.get(".modal-right button").should("contain", "Continue");
     cy.get(".modal-right button").click();

     cy.get(".onboarding").should("contain", "Open Source Contributor?")
     cy.get(".onboarding .skip").should("not.exist");
     cy.get(".onboarding button").should("contain", "Continue");
     cy.get(".onboarding button").click();

     cy.get(".onboarding").should("contain", "Design libraries, styles and components")
     cy.get(".onboarding .skip").should("exist");
     cy.get(".onboarding .step-dots").should("exist");
     cy.get(".onboarding button").should("contain", "Continue");
     cy.get(".onboarding button").click();

     cy.get(".onboarding").should("contain", "Bring your designs to life with interactions")
     cy.get(".onboarding .skip").should("exist");
     cy.get(".onboarding .step-dots").should("exist");
     cy.get(".onboarding button").should("contain", "Continue");
     cy.get(".onboarding button").click();

     
     cy.get(".onboarding").should("contain", "Get feedback, present and share your work")
     cy.get(".onboarding .skip").should("exist");
     cy.get(".onboarding .step-dots").should("exist");
     cy.get(".onboarding button").should("contain", "Continue");
     cy.get(".onboarding button").click();
     
     cy.get(".onboarding").should("contain", "One shared source of truth")
     cy.get(".onboarding .skip").should("not.exist");
     cy.get(".onboarding .step-dots").should("exist");
     cy.get(".onboarding button").should("contain", "Start");
     cy.get(".onboarding button").click();

     cy.get(".onboarding").should("contain", "Welcome to Penpot")
   });

   it("go to specific onboarding slides", () => {    
    cy.get(".modal-right button").click();    
    cy.get(".onboarding button").click();

    cy.get(".step-dots li:nth-child(4)").click();
    cy.get(".onboarding").should("contain", "One shared source of truth")
    cy.get(".step-dots li:nth-child(3)").click();
    cy.get(".onboarding").should("contain", "Get feedback, present and share your work")
    cy.get(".step-dots li:nth-child(2)").click();
    cy.get(".onboarding").should("contain", "Bring your designs to life with interactions")
    cy.get(".step-dots li:nth-child(1)").click();
    cy.get(".onboarding").should("contain", "Design libraries, styles and components")
    
  });

  it("skip onboarding slides", () => {    
    cy.get(".modal-right button").click();    
    cy.get(".onboarding button").click();
    cy.get(".onboarding .skip").click();

    cy.get(".onboarding").should("contain", "Welcome to Penpot")
  });

 });
 
 