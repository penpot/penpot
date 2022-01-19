/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

 "use strict";

 describe("projects", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password)
    });
     
   });
 
   it("displays the projects page", () => {
     cy.get(".dashboard-title").should("contain", "Projects");    
   });

 });
 
 