export const checkOnboardingSlide = (number, checkSkip) => {
    cy.getBySel(`slide-${number}-title`).should("exist");
    if(checkSkip){cy.getBySel("skip-btn").should("exist");}
    cy.get(".onboarding .step-dots").should("exist");
    cy.getBySel(`slide-${number}-btn`).should("exist");
    cy.getBySel(`slide-${number}-btn`).click();
};
export const deleteFirstProject = () => {
    cy.get('.project').first().find("[data-test=project-options]").click();
    cy.wait(500);
    cy.get('.project').first().find("[data-test=project-delete]").click();
    cy.wait(500);
    cy.get('.accept-button').click();
 }

 export const createProject = (projectName="") => {
    cy.getBySel("new-project-button").click();
    cy.wait(500);
    cy.get('.project').first().find(".edit-wrapper").type(projectName + "{enter}");
    cy.wait(500);
 }
 

 export const deleteFirstFile = () => {
   cy.get('.menu')
   .first()
   .trigger('mouseover')
   .click();
   cy.getBySel("file-delete").click();
   cy.get('.accept-button').click();
 }


 export const createFile = (fileName="", projectNum=0) => {   
   cy.getBySel("new-file").click();
   cy.wait(500);
   if (fileName !=""){
      cy.get('.menu')
      .first()
      .trigger('mouseover')
      .click();
      cy.getBySel("file-rename").click();
      cy.get(".edit-wrapper").type(fileName + "{enter}");
      //TODO: Bug workaround. When a file is selected, it doesn't open context menu
      cy.get(".dashboard-grid").click();
   }
 }

 export const createTeam = (teamName) => {
   cy.get(".current-team").click();
    cy.getBySel("create-new-team").click();
    cy.get("#name").type(teamName);
    cy.get("input[type=submit]").click();
    cy.wait(500);
 }
 
 export const deleteCurrentTeam = () => {
   cy.get(".icon-actions").first().click();
   cy.getBySel("delete-team").click();
   cy.get(".accept-button").click();
 }




export const goToSlideByNumber = (number) => {
    cy.get(`.step-dots li:nth-child(${number})`).click();
    cy.getBySel(`slide-${number -1}-btn`).should("exist");
};

export const deleteFirstFont = () => {
  cy.get(".font-item .options").first().click();
  cy.getBySel("font-delete").click();
  cy.get(".accept-button").click();
};