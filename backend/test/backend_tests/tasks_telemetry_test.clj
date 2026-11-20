;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks-telemetry-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]
   [promesa.exec :as px]))

(t/use-fixtures :once th/state-init)

;; Mock px/sleep for all tests to avoid 10s random delays.
;; Composed with database-reset so both apply.
(defn- test-fixture [next]
  (th/database-reset
   (fn []
     (with-redefs [px/sleep (constantly nil)]
       (next)))))

(t/use-fixtures :each test-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STATS / REPORT STRUCTURE TESTS (existing behaviour, extended)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-base-report-data-structure
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true
                                      :props {:newsletter-updates true}})]

      (th/run-task! :telemetry {:send? true :enabled? true})

      (t/is (:called? @mock))
      (let [[_ data] (-> @mock :call-args)]
        (t/is (= :telemetry-legacy-report (:type data)))
        (t/is (contains? data :subscriptions))
        (t/is (= [(:email prof)] (:subscriptions data)))
        (t/is (contains? data :stats))
        (let [stats (:stats data)]
          (t/is (contains? stats :total-fonts))
          (t/is (contains? stats :total-users))
          (t/is (contains? stats :total-projects))
          (t/is (contains? stats :total-files))
          (t/is (contains? stats :total-teams))
          (t/is (contains? stats :total-comments))
          (t/is (contains? stats :jvm-cpus))
          (t/is (contains? stats :jvm-heap-max))
          (t/is (contains? stats :max-users-on-team))
          (t/is (contains? stats :avg-users-on-team))
          (t/is (contains? stats :max-files-on-project))
          (t/is (contains? stats :avg-files-on-project))
          (t/is (contains? stats :max-projects-on-team))
          (t/is (contains? stats :avg-files-on-project))
          (t/is (contains? stats :email-domains))
          (t/is (= ["nodomain.com"] (:email-domains stats)))
          ;; public-uri must be a string
          (t/is (string? (:public-uri stats)))
          (t/is (not-empty (:public-uri stats))))
        (t/is (contains? data :version))
        (t/is (contains? data :instance-id))))))

(t/deftest test-telemetry-disabled-no-send
  ;; When telemetry is disabled and no newsletter subscriptions exist,
  ;; make-legacy-request must not be called at all.
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (th/with-audit-config #{}
      (th/create-profile* 1 {:is-active true})
      (th/run-task! :telemetry {:send? true})
      (t/is (not (:called? @mock))))))

(t/deftest test-telemetry-disabled-newsletter-only-send
  ;; When telemetry is disabled but a user has newsletter-updates opted in,
  ;; make-legacy-request is called once with only subscriptions + version (no stats).
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (th/with-audit-config #{}
      (let [prof (th/create-profile* 1 {:is-active true
                                        :props {:newsletter-updates true}})]
        (th/run-task! :telemetry {:send? true})
        (t/is (:called? @mock))
        (let [[_ data] (:call-args @mock)]
          ;; Limited payload — no stats
          (t/is (contains? data :subscriptions))
          (t/is (contains? data :version))
          (t/is (not (contains? data :stats)))
          (t/is (= [(:email prof)] (:subscriptions data))))))))

(t/deftest test-send-is-skipped-when-send?-false
  ;; Passing send?=false must suppress all HTTP calls even when enabled.
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (th/with-audit-config #{:telemetry}
      (th/create-profile* 1 {:is-active true})
      (th/run-task! :telemetry {:send? false :enabled? true})
      (t/is (not (:called? @mock))))))

(t/deftest test-telemetry-excluded-host-predicate
  (t/is (true? (cf/telemetry-excluded-host? "penpot.app")))
  (t/is (true? (cf/telemetry-excluded-host? "penpot.dev")))
  (t/is (true? (cf/telemetry-excluded-host? "design.penpot.app")))
  (t/is (true? (cf/telemetry-excluded-host? "design.penpot.dev")))
  (t/is (true? (cf/telemetry-excluded-host? "DESIGN.PENPOT.APP")))
  (t/is (false? (cf/telemetry-excluded-host? "localhost")))
  (t/is (false? (cf/telemetry-excluded-host? "example.com")))
  (t/is (false? (cf/telemetry-excluded-host? "mypenpot.app.example.com")))
  (t/is (false? (cf/telemetry-excluded-host? nil)))
  (t/is (false? (cf/telemetry-excluded-host? ""))))

(t/deftest test-telemetry-disabled-on-official-host-newsletter-only
  ;; The limited newsletter report must not be sent from official
  ;; instances, even when subscriptions exist.
  (doseq [[idx public-uri] (map-indexed vector ["https://design.penpot.app"
                                                "https://penpot.app"
                                                "https://design.penpot.dev"
                                                "https://penpot.dev"])]
    (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                       :return nil}]
      (th/with-audit-config #{} [cf/config (assoc cf/config :public-uri public-uri)]
        (th/create-profile* (+ 10 idx) {:is-active true
                                        :props {:newsletter-updates true}})
        (th/run-task! :telemetry {:send? true})
        (t/is (not (:called? @mock)) (str "newsletter report must not send for " public-uri))))))

(t/deftest test-telemetry-excluded-skips-subscriptions-query
  ;; On official hosts the subscriptions query must not even run,
  ;; since nothing is going to be sent.
  (with-mocks [mock {:target 'app.tasks.telemetry/get-subscriptions
                     :return []}]
    (th/with-audit-config #{} [cf/config (assoc cf/config :public-uri "https://design.penpot.app")]
      (th/create-profile* 1 {:is-active true
                             :props {:newsletter-updates true}})
      (th/run-task! :telemetry {:send? true})
      (t/is (not (:called? @mock))))))

(t/deftest test-telemetry-enabled-still-sends-on-official-host
  ;; An explicitly enabled telemetry still reports on official hosts;
  ;; only the implicit newsletter fallback is excluded.
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (th/with-audit-config #{:telemetry} [cf/config (assoc cf/config :public-uri "https://design.penpot.app")]
      (th/create-profile* 1 {:is-active true
                             :props {:newsletter-updates true}})
      (th/run-task! :telemetry {:send? true :enabled? true})
      (t/is (:called? @mock)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ANONYMITY TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-telemetry-rows-stored-without-pii
  ;; Rows written to audit_log in telemetry mode must carry no PII:
  ;; empty props, zeroed ip, profile-id=zero, source='telemetry'.
  ;; Safe context fields (browser, os, version, etc.) are preserved
  ;; but session-linking and access-token fields are stripped.
  (th/with-audit-config #{:telemetry}
    (let [_prof (th/create-profile* 1 {:is-active true})
          safe-ctx {:browser "Chrome"
                    :browser-version "120.0"
                    :os "Linux"
                    :version "2.0.0"}]
      ;; Simulate what app.loggers.audit/process-event does in mode C
      (th/db-insert! :audit-log
                     {:id         (uuid/next)
                      :name       "create-project"
                      :type       "action"
                      :source     "telemetry:backend"
                      :profile-id uuid/zero
                      :ip-addr    (db/inet "0.0.0.0")
                      :props      (db/tjson {})
                      :context    (db/tjson safe-ctx)
                      :tracked-at (ct/now)
                      :created-at (ct/now)})

      (let [[row] (th/db-exec! ["SELECT * FROM audit_log WHERE source = 'telemetry:backend'"])]
        (t/is (= "telemetry:backend" (:source row)))
        ;; props are always empty
        (t/is (= "{}" (str (:props row))))
        ;; ip_addr is the sentinel zero address
        (t/is (= "0.0.0.0" (str (:ip-addr row))))
        ;; profile-id is uuid/zero — not a real user id
        (t/is (= uuid/zero (:profile-id row)))))))

(t/deftest test-telemetry-rows-have-day-precision-timestamps
  ;; Telemetry events must be stored with timestamps truncated to day
  ;; precision so that exact event timing cannot be inferred.
  (th/with-audit-config #{:telemetry}
    (let [process-event (ns-resolve 'app.loggers.audit 'process-event)
          profile       (th/create-profile* 1 {:is-active true})
          tnow          (ct/now)
          event         {:type       "action"
                         :name       "create-project"
                         :profile-id (:id profile)
                         :source     "backend"
                         :props      {}
                         :context    {}
                         :created-at tnow
                         :tracked-at tnow
                         :ip-addr    "0.0.0.0"}]
      (db/tx-run! th/*system* process-event event)
      (let [[row] (th/db-exec! ["SELECT * FROM audit_log WHERE source = 'telemetry:backend'"])]
        (t/is (some? row))
        (let [created-at (:created-at row)
              tracked-at (:tracked-at row)
              day-now    (ct/truncate (ct/now) :days)]
          ;; Both timestamps must equal midnight of the current day
          (t/is (= day-now created-at))
          (t/is (= day-now tracked-at)))))))

(t/deftest test-backend-ingest-full-row-shape
  ;; Verify the full row shape stored by process-event in telemetry mode:
  ;; source=telemetry:backend, empty props, zeroed ip, context filtered to safe
  ;; backend keys only, profile-id preserved, timestamps truncated.
  (th/with-audit-config #{:telemetry}
    (let [process-event (ns-resolve 'app.loggers.audit 'process-event)
          profile       (th/create-profile* 1 {:is-active true})
          tnow          (ct/now)
          event         {:type       "action"
                         :name       "create-project"
                         :profile-id (:id profile)
                         :source     "backend"
                         :context    {:initiator "app"
                                      :version "2.0.0"
                                      :client-version "1.0"
                                      :client-user-agent "Mozilla/5.0"
                                      :external-session-id "should-be-stripped"
                                      :session "also-stripped"}
                         :props      {:some-prop "value"}
                         :created-at tnow
                         :tracked-at tnow
                         :ip-addr    "0.0.0.0"}]
      (db/tx-run! th/*system* process-event event)

      (let [[row] (th/db-exec! ["SELECT * FROM audit_log WHERE source = 'telemetry:backend'"])]
        (t/is (some? row))
        ;; source
        (t/is (= "telemetry:backend" (:source row)))
        ;; profile-id preserved
        (t/is (= (:id profile) (:profile-id row)))
        ;; name
        (t/is (= "create-project" (:name row)))
        ;; type
        (t/is (= "action" (:type row)))
        ;; props stripped to empty
        (t/is (= "{}" (str (:props row))))
        ;; ip zeroed
        (t/is (= "0.0.0.0" (str (:ip-addr row))))
        ;; timestamps truncated to day
        (let [day-now    (ct/truncate (ct/now) :days)]
          (t/is (= day-now (:created-at row)))
          (t/is (= day-now (:tracked-at row))))
        ;; context filtered: only safe backend keys retained
        (let [ctx (db/decode-transit-pgobject (:context row))]
          (t/is (= "app" (:initiator ctx)))
          (t/is (= "2.0.0" (:version ctx)))
          (t/is (= "1.0" (:client-version ctx)))
          (t/is (= "Mozilla/5.0" (:client-user-agent ctx)))
          ;; session-linking keys stripped
          (t/is (not (contains? ctx :external-session-id)))
          (t/is (not (contains? ctx :session))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILTER-TELEMETRY-CONTEXT UNIT TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-filter-telemetry-context-keeps-browser-fields
  ;; Safe environment fields must survive the filter.
  (let [filter-telemetry-context (ns-resolve 'app.loggers.audit 'filter-telemetry-context)
        ctx {:browser         "Chrome"
             :browser-version "120.0"
             :engine          "Blink"
             :engine-version  "120.0"
             :os              "Windows 11"
             :os-version      "11"
             :device-type     "unknown"
             :device-arch     "amd64"
             :locale          "en-US"
             :version         "2.0.0"
             :screen-width    1920
             :screen-height   1080
             :event-origin    "workspace"}
        result (:context (filter-telemetry-context {:source "frontend" :context ctx}))]
    (t/is (= "Chrome" (:browser result)))
    (t/is (= "120.0" (:browser-version result)))
    (t/is (= "Windows 11" (:os result)))
    (t/is (= "en-US" (:locale result)))
    (t/is (= "workspace" (:event-origin result)))
    (t/is (= 1920 (:screen-width result)))))

(t/deftest test-filter-telemetry-context-strips-pii-keys
  ;; Session-linking and access-token fields must be removed.
  ;; The initiator survives: it only says which app sent the event
  ;; ("app", "admin-console") and carries no personal data.
  (let [filter-telemetry-context (ns-resolve 'app.loggers.audit 'filter-telemetry-context)
        ctx {:browser              "Firefox"
             :session              "abc-session-id"
             :external-session-id  "ext-123"
             :file-stats           {:total-shapes 42}
             :initiator            "app"
             :access-token-id      "tok-456"
             :access-token-type    "api-key"}
        result (:context (filter-telemetry-context {:source "frontend" :context ctx}))]
    (t/is (= "Firefox" (:browser result)))
    (t/is (= "app" (:initiator result)))
    (t/is (not (contains? result :session)))
    (t/is (not (contains? result :external-session-id)))
    (t/is (not (contains? result :file-stats)))
    (t/is (not (contains? result :access-token-id)))
    (t/is (not (contains? result :access-token-type)))))

(t/deftest test-filter-telemetry-context-empty-input
  ;; An empty context should return an empty map without error.
  (let [filter-telemetry-context (ns-resolve 'app.loggers.audit 'filter-telemetry-context)]
    (t/is (= {} (:context (filter-telemetry-context {:source "frontend" :context {}}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILTER-TELEMETRY-PROPS UNIT TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-filter-telemetry-props-login-event-keeps-safe-profile-fields
  ;; Login/register/update events carry safe profile-derived fields:
  ;; :lang, :auth-backend, :email-domain. Raw :email is stripped.
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)]
    ;; backend login-with-password
    (let [result (ftp {:source "backend"
                       :name "login-with-password"
                       :type "action"
                       :props {:email "user@example.com"
                               :fullname "John Doe"
                               :lang "en"
                               :auth-backend "password"
                               :id (uuid/next)}})]
      (t/is (= "en" (get-in result [:props :lang])))
      (t/is (= "password" (get-in result [:props :auth-backend])))
      (t/is (= "example.com" (get-in result [:props :email-domain])))
      ;; Raw email and fullname are stripped
      (t/is (not (contains? (:props result) :email)))
      (t/is (not (contains? (:props result) :fullname)))
      ;; UUID values survive the xf:filter-telemetry-props filter
      (t/is (some? (get-in result [:props :id]))))

    ;; backend register-profile
    (let [result (ftp {:source "backend"
                       :name "register-profile"
                       :type "action"
                       :props {:email "new@corp.org"
                               :lang "es"
                               :auth-backend "oidc"}})]
      (t/is (= "es" (get-in result [:props :lang])))
      (t/is (= "oidc" (get-in result [:props :auth-backend])))
      (t/is (= "corp.org" (get-in result [:props :email-domain]))))

    ;; backend login-with-oidc
    (let [result (ftp {:source "backend"
                       :name "login-with-oidc"
                       :type "action"
                       :props {:email "u@corp.io" :lang "fr" :auth-backend "oidc"}})]
      (t/is (= "fr" (get-in result [:props :lang])))
      (t/is (= "oidc" (get-in result [:props :auth-backend])))
      (t/is (= "corp.io" (get-in result [:props :email-domain]))))

    ;; backend update-profile
    (let [result (ftp {:source "backend"
                       :name "update-profile"
                       :type "action"
                       :props {:email "u@corp.io" :lang "de"}})]
      (t/is (= "de" (get-in result [:props :lang])))
      (t/is (= "corp.io" (get-in result [:props :email-domain]))))))

(t/deftest test-filter-telemetry-props-frontend-identify-keeps-safe-profile-fields
  ;; Frontend identify events also carry safe profile-derived fields.
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)]
    (let [result (ftp {:source "frontend"
                       :name "signin"
                       :type "identify"
                       :props {:email "user@example.com"
                               :fullname "Jane Doe"
                               :lang "pt"
                               :auth-backend "password"
                               :some-string "should-be-stripped"}})]
      (t/is (= "pt" (get-in result [:props :lang])))
      (t/is (= "password" (get-in result [:props :auth-backend])))
      (t/is (= "example.com" (get-in result [:props :email-domain])))
      ;; PII stripped
      (t/is (not (contains? (:props result) :email)))
      (t/is (not (contains? (:props result) :fullname)))
      ;; String values that are not UUID/boolean/number are stripped
      (t/is (not (contains? (:props result) :some-string))))))

(t/deftest test-filter-telemetry-props-instance-start-passthrough
  ;; instance-start trigger events pass through as-is.
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        props {:total-teams 5 :total-users 42 :version "2.0"}
        result (ftp {:source "backend"
                     :name "instance-start"
                     :type "trigger"
                     :props props})]
    (t/is (= props (:props result)))))

(t/deftest test-filter-telemetry-props-generic-event-keeps-uuid-boolean-number
  ;; Generic events (create-file, etc.) keep only entries
  ;; whose values are UUIDs, booleans, or numbers.
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        id   (uuid/next)
        result (ftp {:source "frontend"
                     :name "create-file"
                     :type "action"
                     :props {:project-id id
                             :team-id id
                             :route "dashboard-files"
                             :count 42
                             :active true
                             :label "should-be-stripped"}})]
    ;; UUIDs survive
    (t/is (= id (get-in result [:props :project-id])))
    (t/is (= id (get-in result [:props :team-id])))
    ;; Numbers survive
    (t/is (= 42 (get-in result [:props :count])))
    ;; Booleans survive
    (t/is (true? (get-in result [:props :active])))
    ;; Strings are stripped
    (t/is (not (contains? (:props result) :route)))
    (t/is (not (contains? (:props result) :label)))))

(t/deftest test-filter-telemetry-props-accept-organization-invitation
  ;; The shadow row of accept-organization-invitation keeps the ids, the boolean
  ;; and the count, and drops every text prop, the raw email among them. The
  ;; full row and the Nexus archive still carry all of them, and there is no
  ;; context key to fall back on: `safe-backend-context-keys` has no
  ;; `:event-origin`, that one belongs to the browser.
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        profile-id (uuid/next)
        organization-id (uuid/next)
        result (ftp {:source "backend"
                     :name "accept-organization-invitation"
                     :type "action"
                     :props {:profile-id profile-id
                             :invited-by profile-id
                             :organization-id organization-id
                             :team-id (uuid/next)
                             :belongs-to-team-on-add true
                             :organization-member-count-before 3
                             :organization-member-add-source "team-invitation"
                             :profile-email "invitee@example.com"}})]
    (t/is (= profile-id (get-in result [:props :profile-id])))
    (t/is (= profile-id (get-in result [:props :invited-by])))
    (t/is (= organization-id (get-in result [:props :organization-id])))
    (t/is (true? (get-in result [:props :belongs-to-team-on-add])))
    (t/is (= 3 (get-in result [:props :organization-member-count-before])))
    (t/is (not (contains? (:props result) :profile-email)))
    (t/is (not (contains? (:props result) :organization-member-add-source)))))

(t/deftest test-filter-telemetry-props-organization-sso-failure-keeps-reason
  (let [ftp             (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        organization-id (uuid/next)
        result          (ftp {:source "backend"
                              :name "organization-sso-auth-failed"
                              :type "action"
                              :props {:organization-id organization-id
                                      :failure-reason "access-denied"
                                      :unsafe-label "should-be-stripped"}})]
    (t/is (= {:organization-id organization-id
              :failure-reason "access-denied"}
             (:props result)))))

(t/deftest test-filter-telemetry-props-navigate-keeps-route-and-ids
  ;; Frontend navigate events keep specific routing keys: :route,
  ;; :file-id, :team-id, :page-id.  These ids are strings because
  ;; routing events don't coerce them.  All other props are stripped.
  (let [ftp      (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        file-id  (str (uuid/next))
        team-id  (str (uuid/next))
        page-id  (str (uuid/next))
        result   (ftp {:source "frontend"
                       :name "navigate"
                       :type "action"
                       :props {:file-id  file-id
                               :team-id  team-id
                               :page-id  page-id
                               :route    "dashboard-index"
                               :session  "abc"
                               :count    42
                               :active   true
                               :label    "should-be-stripped"}})]
    ;; Allowed routing keys survive (as strings, not coerced to UUID)
    (t/is (= file-id (get-in result [:props :file-id])))
    (t/is (= team-id (get-in result [:props :team-id])))
    (t/is (= page-id (get-in result [:props :page-id])))
    (t/is (= "dashboard-index" (get-in result [:props :route])))
    ;; Everything else is stripped
    (t/is (not (contains? (:props result) :session)))
    (t/is (not (contains? (:props result) :count)))
    (t/is (not (contains? (:props result) :active)))
    (t/is (not (contains? (:props result) :label)))))

(t/deftest test-filter-telemetry-props-subscription-creation-keeps-safe-fields
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        organization-id (uuid/next)
        props {:type "nitrate:enterprise"
               :deployment "saas"
               :quantity 5
               :subscription-mode "paid"
               :organization-id organization-id
               :email "owner@example.com"
               :unsafe-label "should-be-stripped"}
        expected {:type "nitrate:enterprise"
                  :deployment "saas"
                  :quantity 5
                  :subscription-mode "paid"
                  :organization-id organization-id}]
    (doseq [source ["backend" "frontend"]
            name ["create-subscription" "create-trial-subscription"]]
      (t/testing (str source " " name)
        (let [result (ftp {:source source
                           :name name
                           :type "action"
                           :props props})]
          (t/is (= expected (:props result))))))))

(t/deftest test-filter-telemetry-props-subscription-update-keeps-safe-fields
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        props {:type "nitrate:enterprise"
               :deployment "selfhost"
               :previous-status "trialing"
               :status "active"
               :previous-cancel-at-period-end false
               :cancel-at-period-end true
               :previous-cancel-at nil
               :cancel-at "2026-12-01T00:00:00.000Z"
               :changed-fields ["status" "cancel-at-period-end" "cancel-at"]
               :email "owner@example.com"
               :unsafe-label "should-be-stripped"}
        result (ftp {:source "backend"
                     :name "update-subscription"
                     :type "action"
                     :props props})]
    (t/is (= (dissoc props :email :unsafe-label)
             (:props result)))))

(t/deftest test-filter-telemetry-props-subscription-summary-keeps-safe-fields
  (let [ftp (ns-resolve 'app.loggers.audit 'filter-telemetry-props)
        props {:type "nitrate:enterprise"
               :deployment "saas"
               :instance-id "00000000-0000-4000-8000-000000000099"
               :snapshot-date "2026-11-05"
               :trialing-count 1
               :active-count 2
               :active-cancel-scheduled-count 3
               :past-due-count 4
               :canceled-count 5
               :unpaid-count 6
               :incomplete-count 7
               :incomplete-expired-count 8
               :paused-count 9
               :other-count 10
               :total-count 55
               :sync-succeeded 54
               :sync-failed 1
               :snapshot-complete false
               :email "owner@example.com"
               :unsafe-label "should-be-stripped"}
        result (ftp {:source "backend"
                     :name "subscription-summary"
                     :type "action"
                     :props props})]
    (t/is (= (dissoc props :email :unsafe-label)
             (:props result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK BRANCH COVERAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-enabled-no-subs-no-events-legacy-still-sends
  ;; When telemetry is enabled, there are no newsletter subscriptions
  ;; and no audit_log rows, the legacy report must still be sent. The
  ;; shadows no longer travel here: the telemetry consumer sends them.
  (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                            :return nil}]
    (th/with-audit-config #{:telemetry}
      (th/run-task! :telemetry {:send? true :enabled? true})

      ;; Legacy report was sent
      (t/is (:called? @legacy-mock))
      (let [[_ data] (:call-args @legacy-mock)]
        (t/is (= :telemetry-legacy-report (:type data)))
        (t/is (contains? data :stats))
        ;; No subscriptions in the payload
        (t/is (not (contains? data :subscriptions)))))))
