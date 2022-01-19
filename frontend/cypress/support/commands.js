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


Cypress.Commands.add('login', (email, password) => { 
    cy.visit("http://localhost:3449/#/auth/login");
    cy.get("#email").type(email);
    cy.get("#password").type(password);
    cy.get("input[type=submit]").first().click();
 })

 Cypress.Commands.add('demoLogin', () => { 
    cy.visit("http://localhost:3449/#/auth/login");
    cy.get("a").contains("Create demo account").click()
 })