export const interceptRPC = (page, path, jsonFilename) =>
  page.route(`**/api/rpc/command/${path}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: `playwright/fixtures/${jsonFilename}`,
    }),
  );

export const interceptRPCByRegex = (page, regex, jsonFilename) =>
  page.route(regex, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: `playwright/fixtures/${jsonFilename}`,
    }),
  );
