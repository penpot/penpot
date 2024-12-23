(ns frontend-tests.tokens.helpers.tokens
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.token :as wtt]))

(defn get-token [file name]
  (some-> (get-in file [:data :tokens-lib])
          (ctob/get-active-themes-set-tokens)
          (get name)))

(defn apply-token-to-shape
  [file shape-label token-label attributes]
  (let [first-page-id (get-in file [:data :pages 0])
        shape-id (thi/id shape-label)
        token (get-token file token-label)
        applied-attributes (wtt/attributes-map attributes token)]
    (update-in file [:data
                     :pages-index first-page-id
                     :objects shape-id
                     :applied-tokens]
               merge applied-attributes)))
