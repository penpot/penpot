;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.setup.initial-user
  "Initial data setup of instance."
  (:require
   [app.auth :as auth]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.setup :as-alias setup]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(def ^:private sql:insert-profile
  "insert into profile (id, fullname, email, password, is_active, is_admin, created_at, modified_at)
   values ('00000000-0000-0000-0000-000000000000', 'Admin', ?, ?, true, true, now(), now())
       on conflict (id)
       do update set email = ?, password = ?")

(defmethod ig/pre-init-spec ::setup/initial-profile [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::setup/initial-profile
  [_ {:keys [::db/pool]}]
  (let [email    (cf/get :setup-admin-email)
        password (cf/get :setup-admin-password)]
    (when (and email password)
      (db/with-atomic [conn pool]
        (let [pwd (auth/derive-password password)]
          (db/exec-one! conn [sql:insert-profile email pwd email pwd])
          (l/info :hint "setting initial user (admin)"
                  :email email
                  :password "********"))))
    nil))


