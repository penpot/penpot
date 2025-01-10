(ns app.main.data.workspace.tokens.common
  (:require
   [app.main.data.helpers :as dsh]))

(defn get-workspace-tokens-lib
  [state]
  (-> (dsh/lookup-file-data state)
      (get :tokens-lib)))
