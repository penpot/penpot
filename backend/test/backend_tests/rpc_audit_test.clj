;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-audit-test
  (:require
   [app.common.pprint :as pp]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.request]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn decode-row
  [{:keys [props context] :as row}]
  (cond-> row
    (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props))
    (db/pgobject? context) (assoc :context (db/decode-transit-pgobject context))))

(def http-request
  (reify
    yetti.request/IRequest
    (get-header [_ name]
      (case name
        "x-forwarded-for" "127.0.0.44"
        "x-real-ip" "127.0.0.43"))))

(t/deftest prepare-context-initiator-is-plain-string
  ;; The initiator must always be a plain string, never a keyword: shared-key
  ;; authenticated callers (exporter, admin-console) arrive as keywords on
  ;; :app.http/auth-key-id and transit would persist them as "~:exporter".
  (let [base {:headers {"x-forwarded-for" "127.0.0.44"}}]
    (t/is (= "app" (:initiator (audit/prepare-context-from-request base))))
    (t/is (= "exporter"
             (:initiator (audit/prepare-context-from-request
                          (assoc base :app.http/auth-key-id :exporter)))))
    (t/is (= "admin-console"
             (:initiator (audit/prepare-context-from-request
                          (assoc base :app.http/auth-key-id :admin-console)))))
    (t/is (string? (:initiator (audit/prepare-context-from-request
                                (assoc base :app.http/auth-key-id :nexus)))))))

(t/deftest push-events-1
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id (str proj-id)
                                     :team-id (str team-id)
                                     :route "dashboard-files"}
                             :context {:engine "blink"}
                             :profile-id (:id prof)
                             :timestamp (ct/now)
                             :type "action"}]}

          params  (with-meta params
                    {:app.http/request http-request})

          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows)))
        (t/is (= (:id prof) (:profile-id row)))
        (t/is (= "navigate" (:name row)))
        (t/is (= "frontend" (:source row)))))))

(t/deftest push-events-2
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id (str proj-id)
                                     :team-id (str team-id)
                                     :route "dashboard-files"}
                             :context {:engine "blink"}
                             :profile-id uuid/zero
                             :timestamp (ct/now)
                             :type "action"}]}
          params  (with-meta params
                    {:app.http/request http-request})
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows)))
        (t/is (= (:id prof) (:profile-id row)))
        (t/is (= "navigate" (:name row)))
        (t/is (= "frontend" (:source row)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TELEMETRY MODE (frontend ingest)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest push-events-telemetry-mode-stores-anonymized-row
  ;; When telemetry is enabled and audit-log is NOT, frontend events
  ;; must be stored with source="telemetry", empty props, zeroed ip,
  ;; and context filtered to safe keys only.
  (with-redefs [cf/flags #{:telemetry}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id (str proj-id)
                                     :team-id (str team-id)
                                     :route "dashboard-files"}
                             :context {:browser "Chrome"
                                       :browser-version "120.0"
                                       :os "Linux"
                                       :version "2.0.0"
                                       :session "should-be-stripped"
                                       :external-session-id "also-stripped"
                                       :initiator "app"}
                             :timestamp (ct/now)
                             :type "action"}]}

          params  (with-meta params
                    {:app.http/request http-request})
          out     (th/command! params)]

      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        (t/is (= 1 (count rows)))
        ;; source is telemetry:frontend, not frontend
        (t/is (= "telemetry:frontend" (:source row)))
        ;; profile-id preserved
        (t/is (= (:id prof) (:profile-id row)))
        ;; event name preserved
        (t/is (= "navigate" (:name row)))
        ;; navigate events keep route and team-id; other keys stripped
        (t/is (= {:route "dashboard-files"
                  :team-id (str team-id)}
                 (:props row)))
        ;; ip zeroed
        (t/is (= "0.0.0.0" (str (:ip-addr row))))
        ;; timestamps truncated to day precision
        (let [day-now (ct/truncate (ct/now) :days)]
          (t/is (= day-now (:created-at row)))
          (t/is (= day-now (:tracked-at row))))
        ;; context only contains safe keys
        (let [ctx (:context row)]
          (t/is (contains? ctx :browser))
          (t/is (= "Chrome" (:browser ctx)))
          (t/is (contains? ctx :os))
          (t/is (= "Linux" (:os ctx)))
          ;; session-linking keys stripped
          (t/is (not (contains? ctx :session)))
          (t/is (not (contains? ctx :external-session-id))))))))

(t/deftest push-events-both-flags-creates-two-rows
  ;; When both :audit-log and :telemetry flags are active, two rows
  ;; should be stored: one full audit entry and one telemetry entry.
  (with-redefs [cf/flags #{:audit-log :telemetry}]
    (let [prof   (th/create-profile* 1 {:is-active true})
          params {::th/type :push-audit-events
                  ::rpc/profile-id (:id prof)
                  :events [{:name "navigate"
                            :props {:route "dashboard"}
                            :context {:browser "Chrome"
                                      :version "2.0.0"
                                      :initiator "app"}
                            :timestamp (ct/now)
                            :type "action"}]}
          params (with-meta params
                   {:app.http/request http-request})
          out    (th/command! params)]

      (t/is (nil? (:error out)))

      (let [[row1 row2 :as rows] (->> (th/db-exec! ["select * from audit_log order by source"])
                                      (mapv decode-row))]
        (t/is (= 2 (count rows)))
        ;; First row: full audit-log entry
        (t/is (= "frontend" (:source row1)))
        (t/is (contains? (:props row1) :route))
        (t/is (not= "0.0.0.0" (str (:ip-addr row1))))
        ;; Second row: telemetry entry
        (t/is (= "telemetry:frontend" (:source row2)))
        (t/is (= "0.0.0.0" (str (:ip-addr row2))))
        (let [day-now (ct/truncate (ct/now) :days)]
          (t/is (= day-now (:created-at row2)))
          (t/is (= day-now (:tracked-at row2))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BACKEND PROCESS-EVENT PATH (RPC commands)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest backend-process-event-only-audit-log
  (with-redefs [cf/flags #{:audit-log}]
    (let [prof  (th/create-profile* 1 {:is-active true})
          event {:id (uuid/next)
                 :type "action"
                 :name "test-cmd"
                 :profile-id (:id prof)
                 :props {:full-key "full-val"}
                 :context {:version "2.0.0" :initiator "app"}
                 :tracked-at (ct/now)
                 :created-at (ct/now)
                 :source "backend"}]
      (audit/submit* th/*system* event)
      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        (t/is (= 1 (count rows)))
        (t/is (= "backend" (:source row)))
        (t/is (= "full-val" (get-in row [:props :full-key])))
        (t/is (not= "0.0.0.0" (str (:ip-addr row))))))))

(t/deftest backend-process-event-only-telemetry
  (with-redefs [cf/flags #{:telemetry}]
    (let [prof  (th/create-profile* 1 {:is-active true})
          event {:id (uuid/next)
                 :type "action"
                 :name "test-cmd"
                 :profile-id (:id prof)
                 :props {:full-key "full-val"}
                 :context {:version "2.0.0" :initiator "app"}
                 :tracked-at (ct/now)
                 :created-at (ct/now)
                 :source "backend"}]
      (audit/submit* th/*system* event)
      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        (t/is (= 1 (count rows)))
        (t/is (= "telemetry:backend" (:source row)))
        (t/is (= "0.0.0.0" (str (:ip-addr row))))))))

(t/deftest backend-process-event-both-flags-creates-two-rows
  ;; When both :audit-log and :telemetry are active, the backend
  ;; process-event must store two rows: one full audit entry and one
  ;; telemetry entry.
  (with-redefs [cf/flags #{:audit-log :telemetry}]
    (let [prof  (th/create-profile* 1 {:is-active true})
          event {:id (uuid/next)
                 :type "action"
                 :name "test-cmd"
                 :profile-id (:id prof)
                 :props {:keep-me "important"}
                 :context {:version "2.0.0" :initiator "app"}
                 :tracked-at (ct/now)
                 :created-at (ct/now)
                 :source "backend"}]
      (audit/submit* th/*system* event)
      (let [[row1 row2 :as rows] (->> (th/db-exec! ["select * from audit_log order by source"])
                                      (mapv decode-row))]
        (t/is (= 2 (count rows)))
        ;; First row: full audit-log entry
        (t/is (= "backend" (:source row1)))
        (t/is (= "important" (get-in row1 [:props :keep-me])))
        (t/is (not= "0.0.0.0" (str (:ip-addr row1))))
        ;; Second row: telemetry entry
        (t/is (= "telemetry:backend" (:source row2)))
        (t/is (= "0.0.0.0" (str (:ip-addr row2))))
        (let [day-now (ct/truncate (ct/now) :days)]
          (t/is (= day-now (:created-at row2)))
          (t/is (= day-now (:tracked-at row2))))))))

(t/deftest push-events-disabled-when-no-flags-and-no-telemetry
  ;; When neither audit-log nor telemetry is enabled, no rows should
  ;; be stored.
  (with-redefs [cf/flags #{}]
    (let [prof   (th/create-profile* 1 {:is-active true})
          params {::th/type :push-audit-events
                  ::rpc/profile-id (:id prof)
                  :events [{:name "navigate"
                            :props {:route "dashboard"}
                            :timestamp (ct/now)
                            :type "action"}]}
          params (with-meta params
                   {:app.http/request http-request})
          out    (th/command! params)]

      (t/is (nil? (:error out)))
      (t/is (= 0 (count (th/db-exec! ["select * from audit_log"])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET-ENVIRONMENT-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest get-environment-data-returns-selfhost-by-default
  (with-redefs [cf/flags #{:audit-log}]
    (let [out (th/command! {::th/type :get-environment-data})]
      (t/is (th/success? out))
      (t/is (= "selfhost" (-> out :result :deployment))))))

(t/deftest get-environment-data-returns-saas-when-configured
  (with-redefs [cf/flags #{:telemetry}]
    (binding [cf/config (assoc cf/config :is-saas true)]
      (let [out (th/command! {::th/type :get-environment-data})]
        (t/is (th/success? out))
        (t/is (= "saas" (-> out :result :deployment)))))))

(t/deftest get-environment-data-only-exposes-event-flags
  (with-redefs [cf/flags #{:audit-log :telemetry :graph :nrepl-server}]
    (let [out (th/command! {::th/type :get-environment-data})]
      (t/is (th/success? out))
      (t/is (= #{:audit-log :telemetry} (-> out :result :flags))))))

(t/deftest get-environment-data-returns-empty-flags-when-none-enabled
  (with-redefs [cf/flags #{:graph}]
    (let [out (th/command! {::th/type :get-environment-data})]
      (t/is (th/success? out))
      (t/is (= #{} (-> out :result :flags))))))

(t/deftest get-enabled-flags-still-returns-flat-set
  (with-redefs [cf/flags #{:audit-log :telemetry :graph}]
    (let [out (th/command! {::th/type :get-enabled-flags})]
      (t/is (th/success? out))
      (t/is (= #{:audit-log :telemetry} (:result out))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PURE HELPER UNIT TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest extract-utm-params-utm
  ;; UTM params are namespaced under :penpot
  (let [result (audit/extract-utm-params {:utm_source "google"
                                          :utm_medium "cpc"
                                          :utm_campaign "spring"
                                          :other "ignored"})]
    (t/is (= "google" (:penpot/utm-source result)))
    (t/is (= "cpc" (:penpot/utm-medium result)))
    (t/is (= "spring" (:penpot/utm-campaign result)))
    (t/is (not (contains? result :other)))))

(t/deftest extract-utm-params-mtm
  ;; MTM params are also namespaced under :penpot
  (let [result (audit/extract-utm-params {:mtm_source "newsletter"
                                          :mtm_medium "email"})]
    (t/is (= "newsletter" (:penpot/mtm-source result)))
    (t/is (= "email" (:penpot/mtm-medium result)))))

(t/deftest extract-utm-params-empty
  (t/is (= {} (audit/extract-utm-params {})))
  (t/is (= {} (audit/extract-utm-params {:foo "bar" :baz 42}))))

(t/deftest profile->props-selects-and-merges
  ;; Selects profile-props keys and merges with (:props profile)
  (let [profile {:id (uuid/next)
                 :fullname "John"
                 :email "john@example.com"
                 :is-active true
                 :lang "en"
                 :deleted-field "gone"
                 :props {:custom-key "custom-val"
                         :newsletter-updates true}}
        result  (audit/profile->props profile)]
    ;; Selected keys from profile
    (t/is (= "John" (:fullname result)))
    (t/is (= "john@example.com" (:email result)))
    (t/is (true? (:is-active result)))
    (t/is (= "en" (:lang result)))
    ;; Merged from (:props profile)
    (t/is (= "custom-val" (:custom-key result)))
    (t/is (true? (:newsletter-updates result)))
    ;; Keys not in profile-props are excluded
    (t/is (not (contains? result :deleted-field)))))

(t/deftest profile->props-removes-nils
  (let [profile {:id (uuid/next) :fullname nil :email "a@b.com"}
        result  (audit/profile->props profile)]
    (t/is (not (contains? result :fullname)))
    (t/is (= "a@b.com" (:email result)))))

(t/deftest clean-props-removes-reserved
  ;; Reserved props (:session-id, :password, :old-password, :token) are stripped
  (let [props {:name "test"
               :session-id "sess-123"
               :password "secret"
               :old-password "old-secret"
               :token "tok-456"
               :valid-key "kept"}
        result (audit/clean-props props)]
    (t/is (= "test" (:name result)))
    (t/is (= "kept" (:valid-key result)))
    (t/is (not (contains? result :session-id)))
    (t/is (not (contains? result :password)))
    (t/is (not (contains? result :old-password)))
    (t/is (not (contains? result :token)))))

(t/deftest clean-props-removes-qualified-keys
  ;; Qualified keywords (namespaced) are stripped
  (let [props {:simple "kept"
               ::namespaced "stripped"
               :app.rpc/also-stripped true}
        result (audit/clean-props props)]
    (t/is (= "kept" (:simple result)))
    (t/is (not (contains? result ::namespaced)))
    (t/is (not (contains? result :app.rpc/also-stripped)))))

(t/deftest clean-props-removes-nils
  (let [props {:a nil :b "val" :c nil}
        result (audit/clean-props props)]
    (t/is (= "val" (:b result)))
    (t/is (not (contains? result :a)))
    (t/is (not (contains? result :c)))))

(t/deftest get-external-session-id-valid
  (let [request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-external-session-id" "abc-123")))]
    (t/is (= "abc-123" (audit/get-external-session-id request)))))

(t/deftest get-external-session-id-nil-when-missing
  (let [request (reify yetti.request/IRequest
                  (get-header [_ _] nil))]
    (t/is (nil? (audit/get-external-session-id request)))))

(t/deftest get-external-session-id-nil-when-null-string
  (let [request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-external-session-id" "null")))]
    (t/is (nil? (audit/get-external-session-id request)))))

(t/deftest get-external-session-id-nil-when-blank
  (let [request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-external-session-id" "   ")))]
    (t/is (nil? (audit/get-external-session-id request)))))

(t/deftest get-external-session-id-nil-when-too-long
  (let [long-id (apply str (repeat 300 "x"))
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-external-session-id" long-id)))]
    (t/is (nil? (audit/get-external-session-id request)))))

(t/deftest get-client-user-agent-valid
  (let [request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "user-agent" "Mozilla/5.0 (Test)")))]
    (t/is (= "Mozilla/5.0 (Test)" (audit/get-client-user-agent request)))))

(t/deftest get-client-user-agent-nil-when-missing
  (let [request (reify yetti.request/IRequest
                  (get-header [_ _] nil))]
    (t/is (nil? (audit/get-client-user-agent request)))))

(t/deftest get-client-user-agent-truncates-long
  (let [long-ua (apply str (repeat 600 "x"))
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "user-agent" long-ua)))]
    (t/is (<= (count (audit/get-client-user-agent request)) 500))))

(t/deftest get-client-event-origin-valid
  (let [get-client-event-origin (ns-resolve 'app.loggers.audit 'get-client-event-origin)
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-event-origin" "workspace")))]
    (t/is (= "workspace" (get-client-event-origin request)))))

(t/deftest get-client-event-origin-nil-when-null
  (let [get-client-event-origin (ns-resolve 'app.loggers.audit 'get-client-event-origin)
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-event-origin" "null")))]
    (t/is (nil? (get-client-event-origin request)))))

(t/deftest get-client-event-origin-nil-when-blank
  (let [get-client-event-origin (ns-resolve 'app.loggers.audit 'get-client-event-origin)
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-event-origin" "  ")))]
    (t/is (nil? (get-client-event-origin request)))))

(t/deftest get-client-event-origin-truncates-long
  (let [get-client-event-origin (ns-resolve 'app.loggers.audit 'get-client-event-origin)
        long-origin (apply str (repeat 300 "a"))
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-event-origin" long-origin)))]
    (t/is (<= (count (get-client-event-origin request)) 200))))

(t/deftest get-client-version-valid
  (let [get-client-version (ns-resolve 'app.loggers.audit 'get-client-version)
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-frontend-version" "2.0.0")))]
    (t/is (= "2.0.0" (get-client-version request)))))

(t/deftest get-client-version-nil-when-null
  (let [get-client-version (ns-resolve 'app.loggers.audit 'get-client-version)
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-frontend-version" "null")))]
    (t/is (nil? (get-client-version request)))))

(t/deftest get-client-version-nil-when-blank
  (let [get-client-version (ns-resolve 'app.loggers.audit 'get-client-version)
        request (reify yetti.request/IRequest
                  (get-header [_ name]
                    (case name "x-frontend-version" "  ")))]
    (t/is (nil? (get-client-version request)))))

(t/deftest get-client-initiator-from-x-client
  (let [request (fn [client]
                  (reify yetti.request/IRequest
                    (get-header [_ name]
                      (case name "x-client" client))))]
    (t/is (= "app" (audit/get-client-initiator (request nil))))
    (t/is (= "app" (audit/get-client-initiator (request "penpot-frontend/2.19.0"))))
    (t/is (= "admin-console" (audit/get-client-initiator (request "penpot-admin-console/2.19.0"))))
    (t/is (= "admin-console" (audit/get-client-initiator (request "penpot-nitrate/2.19.0"))))
    (t/is (= "admin-console" (audit/get-client-initiator (request "Penpot-Admin-Console/2.19.0"))))
    (t/is (= "app" (audit/get-client-initiator (request "something-else/1.0"))))))

(t/deftest push-events-assigns-initiator-from-x-client
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof   (th/create-profile* 1 {:is-active true})
          params {::th/type :push-audit-events
                  ::rpc/profile-id (:id prof)
                  :events [{:name "navigate"
                            :props {:route "dashboard"}
                            :context {:engine "blink"
                                      :initiator "fake-client-value"}
                            :type "action"
                            :timestamp (ct/now)}]}
          params (with-meta params
                   {:app.http/request
                    (th/make-dummy-request
                     :headers {"x-client" "penpot-admin-console/2.19.0"})})
          out    (th/command! params)]
      (t/is (nil? (:error out)))
      (let [[row] (->> (th/db-exec! ["select * from audit_log"])
                       (mapv decode-row))]
        ;; The server overwrites whatever initiator the client sends.
        (t/is (= "admin-console" (-> row :context :initiator)))))))

(t/deftest push-events-initiator-defaults-to-app
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof   (th/create-profile* 1 {:is-active true})
          params {::th/type :push-audit-events
                  ::rpc/profile-id (:id prof)
                  :events [{:name "navigate"
                            :props {:route "dashboard"}
                            :context {:engine "blink"}
                            :type "action"
                            :timestamp (ct/now)}]}
          params (with-meta params
                   {:app.http/request (th/make-dummy-request)})
          out    (th/command! params)]
      (t/is (nil? (:error out)))
      (let [[row] (->> (th/db-exec! ["select * from audit_log"])
                       (mapv decode-row))]
        (t/is (= "app" (-> row :context :initiator)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INSERT DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest insert-only-runs-with-audit-log-flag
  ;; insert must be a no-op when :audit-log flag is not set
  (with-redefs [app.config/flags #{:telemetry}]
    (audit/insert th/*system* {:name "test" :type "action"})
    (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))))

(t/deftest insert-sets-defaults
  ;; insert must set defaults and persist when :audit-log is set
  (with-redefs [app.config/flags #{:audit-log}]
    (audit/insert th/*system* {:name "test-action" :type "action"})
    (let [[row] (->> (th/db-exec! ["select * from audit_log"])
                     (mapv decode-row))]
      (t/is (some? row))
      (t/is (= "test-action" (:name row)))
      (t/is (= "action" (:type row)))
      (t/is (= "backend" (:source row)))
      (t/is (some? (:id row)))
      (t/is (some? (:created-at row)))
      (t/is (some? (:tracked-at row)))
      (t/is (= {} (:props row)))
      (t/is (= {} (:context row))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PREPARE-RPC-EVENT PROFILE-ID RESOLUTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- prepare-event
  "Call prepare-rpc-event with a bare request, returning the built event."
  [caller result]
  (let [mdata    {::sv/name "test-cmd"}
        params   {::rpc/profile-id caller
                  ::rpc/request-id (uuid/next)
                  ::rpc/request-at (ct/now)}
        mock-req (reify
                   yetti.request/IRequest
                   (get-header [_ _] nil)
                   (remote-addr [_] "127.0.0.1"))
        params   (with-meta params {:app.http/request mock-req})]
    (audit/prepare-rpc-event th/*system* mdata params result)))

(t/deftest prepare-rpc-event-ignores-profile-id-from-result
  ;; An audit event belongs to the caller, never to whatever `:profile-id`
  ;; the response carries. `get-error-report` returns the report with its
  ;; decoded content merged in, and that content can hold the profile that
  ;; owned the report, so honoring it attributed the call to somebody who
  ;; never made it.
  (let [caller (th/create-profile* 1 {:is-active true})]
    (t/is (= (:id caller)
             (:profile-id (prepare-event (:id caller)
                                         {:profile-id "33601240-a00b-11ea-ba1b-c554cc60e361"
                                          :some-data "value"}))))
    ;; an unparseable one must not break the event either
    (t/is (= (:id caller)
             (:profile-id (prepare-event (:id caller)
                                         {:profile-id "not-a-valid-uuid"}))))))

(t/deftest prepare-rpc-event-uses-metadata-profile-id
  ;; `::audit/profile-id` metadata is the only sanctioned override: it is
  ;; how commands that authenticate somebody else (login, verify-token)
  ;; attribute the event to the right profile.
  (let [caller (th/create-profile* 1 {:is-active true})
        target (th/create-profile* 2 {:is-active true})
        result (with-meta {:some-data "value"}
                 {::audit/profile-id (:id target)})]
    (t/is (= (:id target)
             (:profile-id (prepare-event (:id caller) result))))))

(t/deftest prepare-rpc-event-falls-back-to-zero-for-anonymous-callers
  ;; With no metadata and no authenticated caller there is nobody to
  ;; attribute the event to, so it lands on the zero uuid.
  (t/is (= uuid/zero
           (:profile-id (prepare-event nil {:some-data "value"})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PREPARE-RPC-EVENT PROFILE-ID COERCION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest prepare-rpc-event-coerces-string-metadata-profile-id
  ;; `::audit/profile-id` is set by hand in a dozen commands, and some of
  ;; them read the value from token claims or other stringly-typed sources.
  ;; The audit schema demands a uuid, and `submit*` swallows the validation
  ;; error, so an unconverted string would drop the event on the floor.
  (let [caller (th/create-profile* 1 {:is-active true})
        target "33601240-a00b-11ea-ba1b-c554cc60e361"
        result (with-meta {:some-data "value"}
                 {::audit/profile-id target})]
    (t/is (= #uuid "33601240-a00b-11ea-ba1b-c554cc60e361"
             (:profile-id (prepare-event (:id caller) result))))
    (t/is (uuid? (:profile-id (prepare-event (:id caller) result))))))

(t/deftest prepare-rpc-event-discards-unusable-metadata-profile-id
  ;; An override that cannot be turned into a uuid is dropped, not honoured
  ;; and not propagated: the event falls back to the caller, which is always
  ;; a valid uuid, instead of failing the schema check and losing the row.
  (let [caller (th/create-profile* 1 {:is-active true})]
    (doseq [bad ["not-a-valid-uuid" "" "  " 42 {} [] :whatever nil false]]
      (let [result (with-meta {:some-data "value"}
                     (cond-> {::audit/profile-id bad}
                       (nil? bad) (dissoc ::audit/profile-id)))]
        (t/is (= (:id caller)
                 (:profile-id (prepare-event (:id caller) result)))
              (str "override " (pr-str bad) " must fall back to the caller"))))))
