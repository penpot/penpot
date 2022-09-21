/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

 "use strict";

 import {

  deleteFirstFile,
  deleteFirstFont

} from '../../support/utils.js';



describe("comments", () => {
  beforeEach(() => {
  cy.fixture('validuser.json').then((user) => {
      cy.login(user.email, user.password);
  });
    
  });

  it("can open and close comments popup", () => {        
    cy.get(".comments-section").should("not.exist");
    cy.getBySel("open-comments").click();
    cy.get(".comments-section").should("exist");
    cy.getBySel("open-comments").click();
    cy.get(".comments-section").should("not.exist");
  });

});

describe("import and export", () => {
  beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password);
    });
  });

  it("can export a file", () => {        
    cy.get('.menu')
      .first()
      .trigger('mouseover')
      .click();
    cy.getBySel("file-export").click();
    cy.get('.icon-tick').should("exist");
  });

  it("can import a file", () => {
    cy.get(".grid-item").then((files) => {
      cy.get('.project').first().find("[data-test=project-options]").click();
      cy.getBySel("file-import").click();

      cy.uploadBinaryFile("input[type=file]", "test-file-import.penpot");

      cy.get(".accept-button").should('not.be.disabled');
      cy.get(".accept-button").click();
      cy.get(".accept-button").should('not.be.disabled');
      cy.get(".accept-button").click();
      cy.get(".grid-item").should('have.length', files.length+1);
    });
    
    //cleanup
    deleteFirstFile()    ;
  })

})

describe("release notes", () => {
  beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password);
    });
  });

  it("can show release notes", () => {
    cy.get(".profile").click();
    cy.getBySel("profile-profile-opt").click();
    cy.get(".onboarding").should("not.exist");
    cy.getBySel("release-notes").click();
    cy.get(".onboarding").should("exist");
  });
});

describe("fonts", () => {
  beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password);
    });
  });

  it("can upload a font file", () => {  
    cy.getBySel("fonts").click();
    cy.get(".font-item").should('have.length', 0);
    cy.uploadBinaryFile("#font-upload", "fonts/Viafont.otf");
    cy.get(".upload-button").click();
    cy.get(".font-item").should('have.length', 1);
  
    //cleanup
    deleteFirstFont();

  });

  it("can upload multiple font files", () => {  
    cy.getBySel("fonts").click();
    cy.get(".font-item").should('have.length', 0);
    cy.uploadBinaryFile("#font-upload", "fonts/Viafont.otf");
    cy.uploadBinaryFile("#font-upload", "fonts/blkchcry.ttf");
    cy.getBySel("upload-all").click();
    cy.get(".font-item").should('have.length', 2);
  
    //cleanup
    deleteFirstFont();
    deleteFirstFont();
  });

  it("can dismiss multiple font files", () => {  
    cy.getBySel("fonts").click();
    cy.get(".font-item").should('have.length', 0);
    cy.uploadBinaryFile("#font-upload", "fonts/Viafont.otf");
    cy.uploadBinaryFile("#font-upload", "fonts/blkchcry.ttf");
    cy.getBySel("dismiss-all").click();
    cy.get(".font-item").should('have.length', 0);
  });

  it("can rename a font", () => {  
    const fontName = "test font " + Date.now();
    
    //Upload a font
    cy.getBySel("fonts").click();    
    cy.uploadBinaryFile("#font-upload", "fonts/Viafont.otf");
    cy.get(".upload-button").click();
    cy.get(".font-item").should('have.length', 1);

    //Rename font
    cy.get(".font-item .options").first().click();
    cy.getBySel("font-edit").click();
    cy.get(".dashboard-installed-fonts input[value=Viafont]").type(fontName+"{enter}");
    cy.get(".dashboard-installed-fonts").should("contain", fontName);

    //cleanup
    deleteFirstFont();

  });
});