import { interceptRPC } from "./index";


export const setupNotLogedIn = async (page) => {
  await interceptRPC(page, "get-profile", "get-profile-anonymous.json");

};

