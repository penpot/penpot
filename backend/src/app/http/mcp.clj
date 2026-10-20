;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.mcp
  "Backend services authenticated with MCP user tokens."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as email]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.rpc.rlimit :as rlimit]
   [app.setup :as setup]
   [app.tokens :as tokens]
   [clojure.string :as str]
   [integrant.core :as ig]
   [yetti.request :as yreq]
   [yetti.response :as-alias yres]))

(def ^:private valid-feedback?
  (sm/validator
   [:map {:closed true}
    [:subject [:and [:string {:min 1 :max 120}]
               [:fn #(and (not (str/blank? %))
                          (not (re-find #"[\r\n]" %)))]]]
    [:feedback [:and [:string {:min 1 :max 5000}]
                [:fn #(not (str/blank? %))]]]]))

(def ^:private sql:get-profile
  "SELECT p.id, p.fullname, p.email
     FROM access_token AS t
     JOIN profile AS p ON p.id = t.profile_id
    WHERE t.id = ?
      AND t.profile_id = ?
      AND t.type = 'mcp'
      AND (t.expires_at IS NULL OR t.expires_at > now())
      AND p.is_active = true
      AND p.is_blocked = false
      AND (p.deleted_at IS NULL OR p.deleted_at > now())")

(defn- get-profile
  [cfg request]
  (let [token   (some->> (yreq/get-header request "authorization")
                         (re-matches #"(?i)Bearer\s+(\S+)")
                         second)
        ;; Token errors can contain the credential in ex-data. Discard them
        ;; before passing an authentication error to the HTTP error handler.
        claims  (when token
                  (try
                    (tokens/verify cfg {:token token :iss "urn:penpot:mcp-token"})
                    (catch Exception _ nil)))
        profile (when (and (uuid? (:tid claims)) (uuid? (:uid claims)))
                  (db/exec-one! cfg [sql:get-profile (:tid claims) (:uid claims)]))]
    (or profile
        (ex/raise :type :authentication
                  :code :invalid-mcp-token
                  :hint "a valid MCP user token is required"))))

(defn- submit-feedback
  [cfg request]
  (let [profile     (get-profile cfg request)
        destination (cf/get :mcp-feedback-email)
        params      (:body-params request)]
    (if (str/blank? destination)
      {::yres/status 503
       ::yres/body {:code :feedback-disabled
                    :hint "MCP feedback is not configured"}}
      (do
        (when-not (valid-feedback? params)
          (ex/raise :type :validation
                    :code :invalid-feedback
                    :hint "expected a nonblank subject (up to 120 characters, no line breaks) and feedback (up to 5000 characters)"))
        (let [headers (rlimit/enforce-limits cfg (:id profile) (::limits cfg))]
          (email/send cfg {:to destination
                           :reply-to (:email profile)
                           :subject (str "[MCP feedback] " (:subject params))
                           :body {"text/plain" (str "Submitted by: " (:fullname profile)
                                                    " <" (:email profile) ">\n"
                                                    "Profile ID: " (:id profile) "\n\n"
                                                    "Feedback:\n\n" (:feedback params))}})
          {::yres/status 202
           ::yres/headers headers
           ::yres/body {:status "queued"}})))))

(def ^:private schema:routes
  [:map
   ::db/pool
   ::rds/pool
   ::setup/props
   ::jobs/defs
   ::mtx/metrics])

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (sm/check schema:routes params)))

(defmethod ig/init-key ::routes
  [_ cfg]
  (let [limit (assoc (rlimit/parse-limit [:mcp-feedback :window "5/h"])
                     ::rlimit/method "mcp.feedback")]
    ["/api/mcp/feedback"
     {:handler (partial submit-feedback (assoc cfg ::limits [limit]))
      :allowed-methods #{:post}}]))
