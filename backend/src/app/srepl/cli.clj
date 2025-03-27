;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.cli
  "PREPL API for external usage (CLI or ADMIN)"
  (:require
   [app.auth :as auth]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.commands.profile :as cmd.profile]
   [app.util.json :as json]
   [app.util.time :as dt]
   [cuerdas.core :as str]))

(defn- get-current-system
  []
  (or (deref (requiring-resolve 'app.main/system))
      (deref (requiring-resolve 'user/system))))

(defmulti ^:private exec-command ::cmd)

(defn exec
  "Entry point with external tools integrations that uses PREPL
  interface for interacting with running penpot backend."
  [data]
  (let [data (json/decode data)]
    (-> {::cmd (keyword (:cmd data "default"))}
        (merge (:params data))
        (exec-command))))

(defmethod exec-command :create-profile
  [{:keys [fullname email password is-active]
    :or {is-active true}}]
  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [password (cmd.profile/derive-password system password)
                   params   {:id (uuid/next)
                             :email email
                             :fullname fullname
                             :is-active is-active
                             :password password
                             :props {}}]
               (->> (cmd.auth/create-profile! conn params)
                    (cmd.auth/create-profile-rels! conn)))))))

(defmethod exec-command :update-profile
  [{:keys [fullname email password is-active]}]
  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [params (cond-> {}
                            (some? fullname)
                            (assoc :fullname fullname)

                            (some? password)
                            (assoc :password (auth/derive-password password))

                            (some? is-active)
                            (assoc :is-active is-active))]
               (when (seq params)
                 (let [res (db/update! conn :profile
                                       params
                                       {:email email
                                        :deleted-at nil})]
                   (pos? (db/get-update-count res)))))))))

(defmethod exec-command :delete-profile
  [{:keys [email soft]}]
  (when-not email
    (ex/raise :type :assertion
              :code :invalid-arguments
              :hint "email should be provided"))

  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [res (if soft
                         (db/update! conn :profile
                                     {:deleted-at (dt/now)}
                                     {:email email :deleted-at nil})
                         (db/delete! conn :profile
                                     {:email email}))]
               (pos? (db/get-update-count res)))))))

(defmethod exec-command :search-profile
  [{:keys [email]}]
  (when-not email
    (ex/raise :type :assertion
              :code :invalid-arguments
              :hint "email should be provided"))

  (some-> (get-current-system)
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [sql (str "select email, fullname, created_at, deleted_at from profile "
                            " where email similar to ? order by created_at desc limit 100")]
               (db/exec! conn [sql email]))))))

(defmethod exec-command :derive-password
  [{:keys [password]}]
  (auth/derive-password password))

(defmethod exec-command :default
  [{:keys [::cmd]}]
  (ex/raise :type :internal
            :code :not-implemented
            :hint (str/ffmt "command '%' not implemented" (name cmd))))
