(ns app.srepl.dev
  #_:clj-kondo/ignore
  (:require
   [app.db :as db]
   [app.config :as cfg]
   [app.rpc.commands.auth :refer [derive-password]]
   [app.main :refer [system]]))

(defn reset-passwords
  [system]
  (db/with-atomic [conn (:app.db/pool system)]
    (let [password (derive-password "123123")]
      (db/exec! conn ["update profile set password=?" password]))))

