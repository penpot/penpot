;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-error-share-test
  "Tests for stateless error-report share links: the plain renderer,
  the minting command and the anonymous reading command."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.rpc.admin.errors :as admin-errors]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers (same wrapper pattern as rpc-admin-error-reports-test)
;; ----------------------------------------------------------------

(defn- insert-report
  [{:keys [id source content created-at]
    :or {created-at (ct/now)}}]
  (th/db-insert! :server-error-report
                 {:id (or id (uuid/next))
                  :source source
                  :created-at created-at
                  :content (db/tjson content)}))

(defn- raw-method
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.admin.errors)))

(defn- call
  [superusers auth-type profile-id token-perms cmd params]
  (let [[f mdata]   (raw-method cmd)
        cfg         (assoc th/*system* ::auth/superusers superusers)
        request     (assoc (th/make-dummy-request)
                           :params (d/without-qualified params))
        server      (with-meta params {::rpc/request-at (ct/now)
                                       ::http/request request})
        wrapped     (#'rpc/wrap cfg f mdata)]
    (wrapped cfg (assoc server
                        ::rpc/profile-id profile-id
                        ::rpc/auth-type auth-type
                        ::rpc/token-perms token-perms))))

(defn- as-session
  [superusers profile]
  (fn [cmd params]
    (call superusers :session (:id profile) #{} cmd params)))

(defn- as-anonymous
  [cmd params]
  (call #{} nil nil #{} cmd params))

(defn- caught-code
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- mint-token
  "Mint a share token directly, bypassing the command."
  [claims]
  (tokens/generate th/*system* claims))

(defn- token-from-url
  [url]
  (second (str/split url "?token=")))

(defn- run-shared
  "Run `get-shared-error-report` and invoke the returned response
  function, like the RPC dispatcher does."
  [token]
  ((call #{} nil nil #{} "get-shared-error-report" {:token token}) {}))

;; ----------------------------------------------------------------
;; Plain renderer (same shape as the old /dbg/error templates)
;; ----------------------------------------------------------------

(def ^:private sample-report
  {:id (uuid/next)
   :created-at (ct/now)
   :source "logging"
   :profile-id "profile-1"
   :kind "wrong-input"
   :tenant "devenv"
   :version "2.20.1"
   :hint "boom"
   :trace "line1\nline2"
   :context "ctx-line1\nctx-line2"})

(t/deftest render-titles-with-hint-then-identifies
  (let [out   (admin-errors/render-shared-report sample-report "tomorrow")
        lines (str/split out "\n")]
    ;; The hint is the title (like the web detail page) and never a row.
    (t/is (= "# Report: boom" (first lines)))
    (t/is (= "" (second lines)))
    (t/is (= "Id: " (subs (nth lines 2) 0 4)))
    (t/is (str/starts-with? (nth lines 3) "Date: "))
    (t/is (re-find #"^Date: \d{4}-\d{2}-\d{2}T" (nth lines 3)))
    (t/is (= "Source: logging" (nth lines 4)))
    (t/is (nil? (some #(str/starts-with? % "Hint: ") lines)))
    ;; One-liners stay compact rows; long bodies go last, trace first.
    (t/is (some #(= "kind: wrong-input" %) lines))
    (t/is (some #(= "tenant: devenv" %) lines))
    (t/is (some #(= "version: 2.20.1" %) lines))
    (t/is (< (.indexOf lines "## Trace") (.indexOf lines "## Context")))
    (t/is (str/includes? out "## Trace\n\n```\nline1\nline2\n```"))
    (t/is (str/includes? out "## Context\n\n```\nctx-line1\nctx-line2\n```"))
    (t/is (str/ends-with? out "Shared link, expires tomorrow. Single report, no account access."))))

(t/deftest render-uses-first-hint-line-only
  (let [out (admin-errors/render-shared-report {:id 1 :hint "one\ntwo"} "soon")]
    (t/is (str/starts-with? out "# Report: one\n"))))

(t/deftest render-falls-back-without-hint
  (let [out (admin-errors/render-shared-report {:id 1} "soon")]
    (t/is (str/starts-with? out "# Error report\n"))
    (t/is (str/includes? out "Id: 1"))
    (t/is (not (str/includes? out "null")))))

(t/deftest render-steps-fence-up-when-body-has-one
  (let [out (admin-errors/render-shared-report {:id 1 :trace "a\n```\nb"} "soon")]
    (t/is (str/includes? out "## Trace\n\n````\na\n```\nb\n````"))))

(t/deftest render-drops-separator-lines
  (let [out (admin-errors/render-shared-report {:id 1 :trace "a\n====================\nb"} "soon")]
    (t/is (not (str/includes? out "====================")))
    (t/is (str/includes? out "```\na\nb\n```"))))

(t/deftest render-prints-unknown-fields-verbatim
  (let [multi (admin-errors/render-shared-report {:id 1 :weird-key "x\ny"} "soon")
        flat  (admin-errors/render-shared-report {:id 1 :weird-key "x"} "soon")]
    (t/is (str/includes? multi "## Weird-key\n\n```\nx\ny\n```"))
    (t/is (str/includes? flat "weird-key: x"))))

;; ----------------------------------------------------------------
;; Minting command
;; ----------------------------------------------------------------

(t/deftest create-share-returns-url-and-expiry
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)
        id      (uuid/next)]
    (insert-report {:id id :source 3 :content {:hint "boom"}})
    (let [out   (run "create-error-report-share" {:id id})
          token (token-from-url (:url out))]
      (t/is (str/includes? (:url out) "/api/admin/methods/get-shared-error-report?token="))
      (t/is (some? (:expires-at out)))
      (t/is (some? token))
      (let [claims (tokens/verify th/*system* {:token token :iss "error-report-share"})]
        (t/is (= id (:report-id claims)))
        (t/is (= (:id profile) (:created-by claims)))))))

(t/deftest create-share-rejects-ttl-outside-1-to-24
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)
        id      (uuid/next)]
    (insert-report {:id id :source 3 :content {:hint "boom"}})
    (t/is (= :params-validation
             (caught-code #(run "create-error-report-share" {:id id :ttl-hours 0}))))
    (t/is (= :params-validation
             (caught-code #(run "create-error-report-share" {:id id :ttl-hours 25}))))))

(t/deftest create-share-rejects-non-superuser
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "create-error-report-share" {:id (uuid/next)}))))))

(t/deftest create-share-rejects-unknown-id
  (let [profile (th/create-profile* 1)
        run     (as-session #{(:id profile)} profile)]
    (t/is (= :report-not-found
             (caught-code #(run "create-error-report-share" {:id (uuid/next)}))))))

;; ----------------------------------------------------------------
;; Anonymous reading command
;; ----------------------------------------------------------------

(t/deftest shared-command-serves-report-without-session
  (let [id    (uuid/next)
        _     (insert-report {:id id :source 3
                              :content {:hint "boom" :trace "line1\nline2"}})
        token (mint-token {:iss "error-report-share" :report-id id
                           :exp (ct/plus (ct/now) (ct/duration {:hours 4}))})
        res   (run-shared token)]
    (t/is (= 200 (::yres/status res)))
    (t/is (= "text/markdown; charset=utf-8"
             (get (::yres/headers res) "content-type")))
    (t/is (= "no-store" (get (::yres/headers res) "cache-control")))
    (t/is (str/starts-with? (::yres/body res) "# Report: boom"))
    (t/is (str/includes? (::yres/body res) "## Trace\n\n```\nline1\nline2\n```"))))

(t/deftest shared-command-missing-token-param-is-404
  (let [res ((call #{} nil nil #{} "get-shared-error-report" {}) {})]
    (t/is (= 404 (::yres/status res)))
    (t/is (= "not found" (::yres/body res)))))

(t/deftest shared-command-404s-are-indistinguishable
  (let [id      (uuid/next)
        _       (insert-report {:id id :source 3 :content {:hint "boom"}})
        good    (mint-token {:iss "error-report-share" :report-id id
                             :exp (ct/plus (ct/now) (ct/duration {:hours 4}))})
        expired (mint-token {:iss "error-report-share" :report-id id
                             :exp (ct/plus (ct/now) (ct/duration -3600000))})
        foreign (mint-token {:iss "authentication" :report-id id
                             :exp (ct/plus (ct/now) (ct/duration {:hours 4}))})
        missing (mint-token {:iss "error-report-share" :report-id (uuid/next)
                             :exp (ct/plus (ct/now) (ct/duration {:hours 4}))})
        results (mapv run-shared [(str good "tampered") expired foreign missing ""])]
    (t/is (every? #(= 404 (::yres/status %)) results))
    (t/is (every? #(= "not found" (::yres/body %)) results))))
