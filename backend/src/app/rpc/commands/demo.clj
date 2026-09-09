;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.demo
  "A demo specific mutations."
  (:require
   [app.auth :refer [derive-password-weak]]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as auth]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]))

(def ^:private
  schema:create-demo-profile
  [:map
   [:skip-onboarding {:optional true} ::sm/boolean]
   [:expires-in {:optional true} ::ct/duration]])

(def ^:private min-expires-in
  (ct/duration "5m"))

(defn- resolve-deletion-delay
  "Resolve the effective `:demo-purge` delay for a demo profile. Without
  `expires-in` it falls back to the global deletion delay. Otherwise the
  value is only allowed to shorten the lifetime: below the 5 minutes
  minimum or above the global delay it raises a validation error."
  [expires-in]
  (let [max-delay (cf/get-deletion-delay)]
    (cond
      (nil? expires-in)
      max-delay

      (ct/is-before? expires-in min-expires-in)
      (ex/raise :type :validation
                :code :invalid-expires-in
                :hint "expires-in is below the 5 minutes minimum.")

      (ct/is-after? expires-in max-delay)
      (ex/raise :type :validation
                :code :invalid-expires-in
                :hint "expires-in exceeds the configured deletion delay.")

      :else
      expires-in)))

(sv/defmethod ::create-demo-profile
  "A command that is responsible of creating a demo purpose
  profile. It only works if the `demo-users` flag is enabled in the
  configuration."
  {::rpc/auth false
   ::doc/added "1.15"
   ::doc/changes [["1.15" "This method is migrated from mutations to commands."]
                  ["2.18" "Add optional `skip-onboarding` param. When true, the profile is created with `onboarding-viewed` and `release-notes-viewed` (current version) set, skipping the onboarding flow."]
                  ["2.20" "Add optional `expires-in` param. When set, the demo purge is scheduled that long after creation instead of the global deletion delay. Only values between 5 minutes and the global delay are accepted."]]
   ::sm/params schema:create-demo-profile}
  [cfg {:keys [skip-onboarding expires-in]}]

  (when-not (contains? cf/flags :demo-users)
    (ex/raise :type :validation
              :code :demo-users-not-allowed
              :hint "Demo users are disabled by config."))

  (let [deletion-delay (resolve-deletion-delay expires-in)
        sem            (uuid/next)
        email          (str "demo-" sem "@demo.example.com")
        fullname       (str "Demo User " sem)

        password       (-> (bn/random-bytes 16)
                           (bc/bytes->b64 true)
                           (bc/bytes->str))

        params         {:email email
                        :fullname fullname
                        :is-active true
                        :is-demo true
                        :password (derive-password-weak password)
                        :props (cond-> {}
                                 skip-onboarding (assoc :onboarding-viewed true
                                                        ;; Redundant today: auth/create-profile
                                                        ;; overwrites this with the current
                                                        ;; version, kept so the skip does not
                                                        ;; depend on that default.
                                                        :release-notes-viewed (:main cf/version)))}
        profile        (db/tx-run! cfg (fn [cfg]
                                         (->> (auth/create-profile cfg params)
                                              (auth/create-profile-rels cfg))))]

    (wrk/submit! (-> cfg
                     (assoc ::wrk/task :demo-purge)
                     (assoc ::wrk/delay deletion-delay)
                     (assoc ::wrk/params {:profile-id (:id profile)})))

    (with-meta {:email email
                :password password}
      {::audit/profile-id (:id profile)})))
