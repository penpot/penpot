(ns app.srepl.dev
  #_:clj-kondo/ignore
  (:require
   [app.db :as db]
   [app.config :as cfg]
   [app.rpc.mutations.profile :refer [derive-password]]
   [app.main :refer [system]]))

(defn reset-passwords
  [system]
  (when (not= "devenv" (:host cfg/config))
    (throw (ex-info "Can't proceed, only allowed this operation on devenv" {})))
  (db/with-atomic [conn (:app.db/pool system)]
    (let [password (derive-password "123123")]
      (db/exec! conn ["update profile set password=?" password]))))

