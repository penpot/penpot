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
  deleteFirstProject,
  deleteFirstFile,
  createFile

} from '../../support/utils.js';



 describe("files", () => {
   beforeEach(() => {
    cy.fixture('validuser.json').then((user) => {
        cy.login(user.email, user.password);
        createProject("test project" + Date.now());
    });
     
   });

   afterEach(() => {
     //cleanup
     deleteFirstProject();
   });
 

   it("can create a new file", () => {
    cy.get(".grid-item").then((files) => {
      cy.get('.project').first().find("[data-test=project-new-file]").click();
      cy.get("#workspace").should("exist");
      cy.get(".project-tree").should("contain", "New File");

      //Go back
      cy.get(".main-icon a").click();
      cy.get(".grid-item").should('have.length', files.length + 1);
    })
      
  })

  it("can create a new file inside a project", () => {
    cy.get(".project").first().find("h2").click();
    cy.get(".grid-item").should('have.length', 0);
    createFile();
    cy.get(".grid-item").should('have.length', 1);

    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can create a new file inside a project with shortcut", () => {
    cy.get(".project").first().find("h2").click();
    cy.get(".grid-item").should('have.length', 0);
    cy.get("body").type("+");
    cy.get(".grid-item").should('have.length', 1);

    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can delete a file inside a project", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    cy.get(".grid-item").should('have.length', 1);
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-delete").click();
    cy.get('.accept-button').click();      
    cy.get(".grid-item").should('have.length', 0);    

    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can cancel a file deletion inside a project", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    cy.get(".grid-item").should('have.length', 1);
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-delete").click();
    cy.get('.cancel-button').click();      
    cy.get(".grid-item").should('have.length', 1);    

    //Go back
    cy.get(".recent-projects").click();                  
  })


  it("can delete a file outside a project", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    
    //Go back
    cy.get(".recent-projects").click();

    cy.get(".grid-item").then((files) => {
      cy.get('.menu')
        .first()
        .trigger('mouseover')
        .click();
      cy.getBySel("file-delete").click();
      cy.get('.accept-button').click();      
      cy.get(".grid-item").should('have.length', files.length-1);
    });               
  })

  it("can cancel a file deletion outside a project", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    
    //Go back
    cy.get(".recent-projects").click();

    cy.get(".grid-item").then((files) => {
      cy.get('.menu')
        .first()
        .trigger('mouseover')
        .click();
      cy.getBySel("file-delete").click();
      cy.get('.cancel-button').click();      
      cy.get(".grid-item").should('have.length', files.length);    
    });               
  })

  it("can rename a file", () => {
    const fileName = "test file " + Date.now(); 

    cy.get(".project").first().find("h2").click();
    createFile();
    
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-rename").click();
    cy.get(".edit-wrapper").should("exist");
    cy.get(".edit-wrapper").type(fileName + "{enter}");
    
    cy.get(".grid-item").first().should("contain", fileName);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can duplicate a file", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    
    cy.get(".grid-item").should('have.length', 1);
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-duplicate").click();
    cy.get(".grid-item").should('have.length', 2);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })


  it("can move a file to another project", () => {
    const projectToMoveName = "test project to move " + Date.now();
    const fileName = "test file " + Date.now();

    createProject(projectToMoveName);
    cy.get(".project").eq(1).find("h2").click();
    createFile(fileName);

    //TODO: Bug workaround. When a file is selected, it doesn't open context menu
    cy.get(".dashboard-grid").click();

    
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.wait(500);
    cy.getBySel("file-move-to").click();
    
    cy.get('a').contains(projectToMoveName).click();

    cy.getBySel("project-title").should("contain", projectToMoveName);
    cy.get(".grid-item").should('have.length', 1);
    cy.get(".grid-item").first().should("contain", fileName);
      

    //Go back and cleanup: delete project
    cy.get(".recent-projects").click();  
    deleteFirstProject();   
  });


  it("can move a file to another team", () => {
    const fileName = "test file " + Date.now();
    cy.get(".project").first().find("h2").click();
    createFile(fileName);    

    //TODO: Bug workaround. When a file is selected, it doesn't open context menu
    cy.get(".dashboard-grid").click();

    
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.wait(500);
    cy.getBySel("file-move-to").click();
    
    cy.getBySel("move-to-other-team").click();
    cy.fixture('validuser.json').then((user) => {
      cy.get('a').contains(user.team).click();          
      cy.get('a').contains("Drafts").click();
      cy.get(".current-team").should("contain", user.team);
      cy.get(".dashboard-title").should("contain", "Drafts");
      cy.get(".grid-item").first().should("contain", fileName);
    });
      

    //cleanup
    deleteFirstFile();
    cy.get(".current-team").click();
    cy.get(".team-name").contains("Your Penpot").click();
  });


  it("can make a file a shared library", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    
    cy.get(".icon-library").should('have.length', 0);
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-add-shared").click();
    cy.get(".accept-button").click();
    cy.get(".icon-library").should('have.length', 1);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can cancel make a file a shared library", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    
    cy.get(".icon-library").should('have.length', 0);
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-add-shared").click();
    cy.get(".modal-close-button").click();
    cy.get(".icon-library").should('have.length', 0);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })


  it("can remove a file as shared library", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
        
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-add-shared").click();
    cy.get(".accept-button").click();
    cy.get(".icon-library").should('have.length', 1);

    //TODO: Bug workaround. When a file is selected, it doesn't open context menu
    cy.get(".dashboard-grid").click();

    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-del-shared").click();
    cy.get(".accept-button").click();
    cy.get(".icon-library").should('have.length', 0);

    
    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can cancel remove a file as shared library", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
        
    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-add-shared").click();
    cy.get(".accept-button").click();
    cy.get(".icon-library").should('have.length', 1);

    //TODO: Bug workaround. When a file is selected, it doesn't open context menu
    cy.get(".dashboard-grid").click();

    cy.get('.menu')
      .trigger('mouseover')
      .click();
    cy.getBySel("file-del-shared").click();
    cy.get(".modal-close-button").click();
    cy.get(".icon-library").should('have.length', 1);

    
    //Go back
    cy.get(".recent-projects").click();                  
  })


  it("can search for a file", () => {
    const fileName = "test file " + Date.now();

    cy.get(".project").first().find("h2").click();
    createFile(fileName);
    
    cy.get("#search-input").type("bad name");
    cy.get(".grid-item").should('have.length', 0);

    cy.get("#search-input").clear().type(fileName);
    cy.get(".grid-item").should('have.length', 1);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })


  it("can multiselect files", () => {
    cy.get(".project").first().find("h2").click();
    createFile();    
    createFile();
    createFile();

    cy.get(".selected").should('have.length', 0);

    cy.get(".grid-item").eq(0).click({shiftKey: true});
    cy.get(".selected").should('have.length', 1);

    cy.get(".grid-item").eq(2).click({shiftKey: true});
    cy.get(".selected").should('have.length', 2);

    cy.get(".grid-item").eq(1).click({shiftKey: true});
    cy.get(".selected").should('have.length', 3);

    cy.get(".grid-item").eq(1).click({shiftKey: true});
    cy.get(".selected").should('have.length', 2);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can delete multiselected files", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    createFile();
    createFile();

    cy.get(".grid-item").eq(0).click({shiftKey: true});
    cy.get(".grid-item").eq(2).click({shiftKey: true});
    
    cy.get(".grid-item").should('have.length', 3);
    cy.get(".grid-item").eq(0).rightclick();
    cy.getBySel("delete-multi-files").should("contain", "Delete 2 files");
    cy.getBySel("delete-multi-files").click();
    cy.get('.accept-button').click();      
    cy.get(".grid-item").should('have.length', 1);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can cancel delete multiselected files", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    createFile();
    createFile();

    cy.get(".grid-item").eq(0).click({shiftKey: true});
    cy.get(".grid-item").eq(2).click({shiftKey: true});
    
    cy.get(".grid-item").should('have.length', 3);
    cy.get(".grid-item").eq(0).rightclick();
    cy.getBySel("delete-multi-files").should("contain", "Delete 2 files");
    cy.getBySel("delete-multi-files").click();
    cy.get('.cancel-button').click();      
    cy.get(".grid-item").should('have.length', 3);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })


  it("can duplicate multiselected files", () => {
    cy.get(".project").first().find("h2").click();
    createFile();
    createFile();
    createFile();

    cy.get(".grid-item").eq(0).click({shiftKey: true});
    cy.get(".grid-item").eq(2).click({shiftKey: true});
    
    cy.get(".grid-item").should('have.length', 3);
    cy.get(".grid-item").eq(0).rightclick();
    cy.getBySel("duplicate-multi").should("contain", "Duplicate 2 files");
    cy.getBySel("duplicate-multi").click();  
    cy.get(".grid-item").should('have.length', 5);
    
    //Go back
    cy.get(".recent-projects").click();                  
  })

  it("can move multiselected files to another project", () => {
    const projectToMoveName = "test project to move " + Date.now();   
    createProject(projectToMoveName);

    cy.get(".project").eq(1).find("h2").click();
    createFile();
    createFile();
    createFile();

    cy.get(".grid-item").eq(0).click({shiftKey: true});
    cy.get(".grid-item").eq(2).click({shiftKey: true});
    
    
    cy.get(".grid-item").eq(0).rightclick();
    cy.getBySel("move-to-multi").should("contain", "Move 2 files to");
    cy.getBySel("move-to-multi").click();
    cy.get('a').contains(projectToMoveName).click();

    cy.getBySel("project-title").should("contain", projectToMoveName);
    cy.get(".grid-item").should('have.length', 2);

    
    //Go back
    cy.get(".recent-projects").click();
    deleteFirstProject();
  })


  it("can move multiselected files to another team", () => {
    const fileName1 = "test file " + Date.now();
    const fileName2 = "test file " + Date.now();
    const fileName3 = "test file " + Date.now();

    cy.get(".project").first().find("h2").click();
    createFile(fileName1)
    createFile(fileName2)
    createFile(fileName3)
    

    //multiselect first and third file
    cy.get(".grid-item").eq(0).click({shiftKey: true});
    cy.get(".grid-item").eq(2).click({shiftKey: true});
    
    
    cy.get(".grid-item").eq(0).rightclick();
    cy.getBySel("move-to-multi").should("contain", "Move 2 files to");
    cy.getBySel("move-to-multi").click();
    cy.getBySel("move-to-other-team").click();
    cy.fixture('validuser.json').then((user) => {
      cy.get('a').contains(user.team).click();          
      cy.get('a').contains("Drafts").click();
      cy.get(".current-team").should("contain", user.team);
      cy.get(".dashboard-title").should("contain", "Drafts");
      cy.get(".grid-item").eq(0).should("contain", fileName1);
      cy.get(".grid-item").eq(1).should("contain", fileName2);
    });
      

    //cleanup
    deleteFirstFile()
    deleteFirstFile()
    cy.get(".current-team").click();
    cy.get(".team-name").contains("Your Penpot").click();
  })

 });
 
 
