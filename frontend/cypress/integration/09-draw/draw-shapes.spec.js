/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

 "use strict";

 describe("draw shapes", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password)
        cy.get(".project-th").first().dblclick()
        cy.clearViewport();
    });
   });
 
   it("draw an artboard", () => {    
     cy.get(".viewport-controls rect").should("not.exist");
     cy.get(".left-toolbar-options li[alt='Artboard (A)']").click()
     cy.drawInViewport(300, 300, 400, 450)
     cy.get(".viewport-controls rect").first().as("artboard");     
     cy.get("@artboard").should("exist");
     cy.get("@artboard").invoke('attr', 'width').should('eq', '100')
     cy.get("@artboard").invoke('attr', 'height').should('eq', '150')     
   });

   it("draw a square", () => {
    cy.get(".viewport-controls rect").should("not.exist");
    cy.get(".left-toolbar-options li[alt='Rectangle (R)']").click()
    cy.drawInViewport(300, 300, 400, 450)
    cy.get(".viewport-controls rect").should("exist");
    cy.get(".viewport-controls rect").invoke('attr', 'width').should('eq', '100')
    cy.get(".viewport-controls rect").invoke('attr', 'height').should('eq', '150')     
  });

  it("draw an ellipse", () => {
    cy.get(".viewport-controls ellipse").should("not.exist");
    cy.get(".left-toolbar-options li[alt='Ellipse (E)']").click()
    cy.drawInViewport(300, 300, 400, 450)
    cy.get(".viewport-controls ellipse").as("ellipse")
    cy.get("@ellipse").should("exist");
    cy.get("@ellipse").invoke('attr', 'rx').should('eq', '50')
    cy.get("@ellipse").invoke('attr', 'ry').should('eq', '75')     
  });

  it("draw a curve", () => {
    cy.get(".viewport-controls path").should("not.exist");
    cy.get(".left-toolbar-options li[alt='Curve (Shift+C)']").click()
    cy.drawMultiInViewport([{x:300, y:300}, {x:350, y:300}, {x:300, y:350}, {x:400, y:450}])
    cy.get(".viewport-controls path").as("curve")
    cy.get("@curve").should("exist");
    cy.get("@curve").invoke('attr', 'd').should('eq', "M300,300L350,300L300,350L400,450")
  });

  it("draw a path", () => {
    cy.get(".viewport-controls path").should("not.exist");
    cy.get(".left-toolbar-options li[alt='Path (P)']").click()
    cy.clickMultiInViewport([{x:300, y:300}, {x:350, y:300}])
    cy.drawMultiInViewport([{x:400, y:450}, {x:450, y:450}], true)
    cy.clickMultiInViewport([{x:300, y:300}])
    cy.get(".viewport-controls path").as("curve")
    cy.get("@curve").should("exist");
    cy.get("@curve").invoke('attr', 'd').should('eq', "M300,300L350,300C350,300,350,450,400,450C450,450,300,300,300,300Z")
  });

 });
