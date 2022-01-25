// ***********************************************
// This example commands.js shows you how to
// create various custom commands and overwrite
// existing commands.
//
// For more comprehensive examples of custom
// commands please read more here:
// https://on.cypress.io/custom-commands
// ***********************************************
//
//
// -- This is a parent command --
// Cypress.Commands.add('login', (email, password) => { ... })
//
//
// -- This is a child command --
// Cypress.Commands.add('drag', { prevSubject: 'element'}, (subject, options) => { ... })
//
//
// -- This is a dual command --
// Cypress.Commands.add('dismiss', { prevSubject: 'optional'}, (subject, options) => { ... })
//
//
// -- This will overwrite an existing command --
// Cypress.Commands.overwrite('visit', (originalFn, url, options) => { ... })
import 'cypress-file-upload';

Cypress.Commands.add('login', (email, password) => { 
    cy.visit("http://localhost:3449/#/auth/login");
    cy.get("#email").type(email);
    cy.get("#password").type(password);
    cy.getBySel("login-submit").click();
 })

 Cypress.Commands.add('demoLogin', () => { 
    cy.visit("http://localhost:3449/#/auth/login");
    cy.getBySel("demo-account-link").click()
 })

 Cypress.Commands.add('drawInViewport', (x1, y1, x2, y2) => {
   cy.get(".viewport-controls")
       .trigger('mousemove', { x: x1, y: y1 })
       .trigger('mousedown', {
           x: x1, 
           y: y1,
           which: 1
       })
       .trigger('mousemove', { x: x2, y: y2 })
       .trigger('mouseup', { x: x2, y: y2, which: 1 });
})

Cypress.Commands.add('drawMultiInViewport', (coords, force=false) => {
   cy.get(".viewport-controls")
       .trigger('mousemove', { x: coords[0].x, y: coords[0].y, force: force})
       .trigger('mousedown', {
           x: coords[0].x, 
           y: coords[0].y,
           which: 1,
           force: force
       });
   
   for (var i=1; i<coords.length; i++){
      cy.get(".viewport-controls").trigger('mousemove', { x: coords[i].x, y: coords[i].y, force: force })
   }

   cy.get(".viewport-controls").trigger('mouseup', { 
      x: coords[coords.length-1].x, 
      y: coords[coords.length-1].y, 
      which: 1,
   force: force });
})


function click(x, y) {
   cy.get(".viewport-controls")
      .trigger('mousemove', { x: x, y: y })
      .trigger('mousedown', {x: x, y: y, which: 1})
      .trigger('mouseup', {x: x, y: y, which: 1});

 }

Cypress.Commands.add('clickMultiInViewport', (coords) => {   
   for (var i=0; i<coords.length; i++){
      click(coords[i].x, coords[i].y);
   }
})

Cypress.Commands.add('clearViewport', () => {
   cy.get(".viewport-controls").type('{ctrl}a');
   cy.get(".viewport-controls").type('{del}');
   cy.window().its("debug").invoke('reset_viewport');
})

Cypress.Commands.add('getBySel', (selector, ...args) => {
   return cy.get(`[data-test=${selector}]`, ...args)
})

Cypress.Commands.add('getBySelLike', (selector, ...args) => {
   return cy.get(`[data-test*=${selector}]`, ...args)
})

Cypress.Commands.add('uploadBinaryFile', (fileInputSelector, fileName) => {
   cy.fixture(fileName, "binary")
   .then(Cypress.Blob.binaryStringToBlob)
   .then(fileContent => {
      cy.get(fileInputSelector).attachFile({
         fileContent,
         filePath: fileName,
         encoding: 'utf-8',
         lastModified: new Date().getTime()
      });
   });
})

