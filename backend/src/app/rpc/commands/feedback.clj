;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.feedback
  "A general purpose feedback module."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.services :as sv]
   [clojure.string :as str]))

(def schema:send-mcp-feedback
  [:map {:title "send-mcp-feedback" :closed true}
   [:subject [:and [:string {:min 1 :max 120}]
              [:fn #(and (not (str/blank? %))
                         (not (re-find #"\p{Cc}" %)))]]]
   ;; Line breaks and tabs are the only control characters allowed
   [:feedback [:and [:string {:min 1 :max 5000}]
               [:fn #(and (not (str/blank? %))
                          (not (re-find #"[\p{Cc}&&[^\t\n\r]]" %)))]]]])

(sv/defmethod ::send-mcp-feedback
  {::rpc/token-type "mcp"
   ::db/transaction true
   ::doc/added "2.20"
   ::sm/params schema:send-mcp-feedback}
  [cfg {:keys [::rpc/profile-id subject feedback]}]
  (let [profile     (profile/get-profile cfg profile-id)
        destination (cf/get :mcp-feedback-email)]
    ;; Blocking a profile invalidates its sessions but not its access tokens
    (when (:is-blocked profile)
      (ex/raise :type :authorization
                :code :profile-blocked
                :hint "profile is marked as blocked"))
    (when-not (:is-active profile)
      (ex/raise :type :authorization
                :code :profile-not-active
                :hint "profile is not active"))
    (when (str/blank? destination)
      (ex/raise :type :restriction
                :code :feedback-disabled
                :hint "MCP feedback is not configured"))
    (eml/send cfg {::eml/reuse-conn true
                   :to destination
                   :reply-to (:email profile)
                   :subject (str "[MCP feedback] " subject)
                   :body {"text/plain" (str "Submitted by: " (:fullname profile)
                                            " <" (:email profile) ">\n"
                                            "Profile ID: " (:id profile) "\n\n"
                                            "Feedback:\n\n" feedback)}})
    ;; The audit event keeps who sent feedback, not its text
    (rph/with-meta {:status "queued"}
      {::audit/replace-props {}})))

(declare ^:private send-user-feedback!)

(def schema:send-user-feedback
  [:map {:title "send-user-feedback"}
   [:subject [:string {:max 500}]]
   [:content [:string {:max 2500}]]
   [:type {:optional true} :string]
   [:error-href {:optional true} [:string {:max 2500}]]
   [:error-report {:optional true} [:string {:max 1048576}]]])

(sv/defmethod ::send-user-feedback
  {::climit/id [[:send-user-feedback/by-profile ::rpc/profile-id]
                [:send-user-feedback/global]]
   ::db/transaction true
   ::doc/added "1.18"
   ::sm/params schema:send-user-feedback}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (when-not (contains? cf/flags :user-feedback)
    (ex/raise :type :restriction
              :code :feedback-disabled
              :hint "feedback not enabled"))

  (let [pool    (::db/pool cfg)
        profile (profile/get-profile pool profile-id)]
    (send-user-feedback! cfg profile params)
    nil))

(defn- send-user-feedback!
  [cfg profile params]
  (let [destination
        (or (cf/get :user-feedback-destination)
            ;; LEGACY
            (cf/get :feedback-destination))

        attachments
        (d/without-nils
         {"error-report.txt" (:error-report params)})]

    (eml/send cfg {::eml/reuse-conn true
                   ::eml/factory eml/user-feedback
                   :to       destination
                   :reply-to (:email profile)
                   :email    (:email profile)
                   :attachments attachments

                   :feedback-subject (:subject params)
                   :feedback-type (:type params "not-specified")
                   :feedback-content (:content params)
                   :feedback-error-href (:error-href params)
                   :profile profile})
    nil))
