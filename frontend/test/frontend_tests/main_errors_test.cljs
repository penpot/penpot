;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.main-errors-test
  "Unit tests for app.main.errors.

  Tests cover:
    - stale-asset-error?          – pure predicate
    - exception->error-data       – pure transformer
    - on-error re-entrancy guard  – prevents recursive invocations
    - flash schedules async report and toast – neither the report nor
      ntf/show is emitted synchronously
    - organization SSO recovery   – expired SSO sessions go back to the provider
    - invalid-sso-config handler  – requires :organization-id to promote to :sso-error
    - save failure notification   – sticky toast carrying the error report
    - delegated save failures     – causes handled by the handler for their type"
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace :as-alias dw]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

;; ---------------------------------------------------------------------------
;; stale-asset-error?
;; ---------------------------------------------------------------------------

(t/deftest stale-asset-error-nil
  (t/testing "nil cause returns nil/falsy"
    (t/is (not (errors/stale-asset-error? nil)))))

(t/deftest stale-asset-error-keyword-cst-undefined
  (t/testing "error with $cljs$cst$ and 'is undefined' is recognised"
    (let [err (js/Error. "foo$cljs$cst$bar is undefined")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-keyword-cst-null
  (t/testing "error with $cljs$cst$ and 'is null' is recognised"
    (let [err (js/Error. "foo$cljs$cst$bar is null")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-protocol-dispatch-undefined
  (t/testing "error with $cljs$core$I and 'Cannot read properties of undefined' is recognised"
    (let [err (js/Error. "Cannot read properties of undefined (reading '$cljs$core$IFn$_invoke$arity$1$')")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-not-a-function
  (t/testing "error with $cljs$cst$ and 'is not a function' is recognised"
    (let [err (js/Error. "foo$cljs$cst$bar is not a function")]
      (t/is (true? (boolean (errors/stale-asset-error? err)))))))

(t/deftest stale-asset-error-unrelated-message
  (t/testing "ordinary error without stale-asset signature is NOT recognised"
    (let [err (js/Error. "Cannot read properties of undefined (reading 'foo')")]
      (t/is (not (errors/stale-asset-error? err))))))

(t/deftest stale-asset-error-only-cst-no-undefined
  (t/testing "error with $cljs$cst$ but no undefined/null/not-a-function keyword is not recognised"
    (let [err (js/Error. "foo$cljs$cst$bar exploded")]
      (t/is (not (errors/stale-asset-error? err))))))

;; ---------------------------------------------------------------------------
;; exception->error-data
;; ---------------------------------------------------------------------------

(t/deftest exception->error-data-plain-error
  (t/testing "plain JS Error is converted to a data map with :hint and ::instance"
    (let [err  (js/Error. "something went wrong")
          data (errors/exception->error-data err)]
      (t/is (= "something went wrong" (:hint data)))
      (t/is (identical? err (::errors/instance data))))))

(t/deftest exception->error-data-ex-info
  (t/testing "ex-info error preserves existing :hint and attaches ::instance"
    (let [err  (ex-info "original" {:hint "my-hint" :type :network})
          data (errors/exception->error-data err)]
      (t/is (= "my-hint" (:hint data)))
      (t/is (= :network (:type data)))
      (t/is (identical? err (::errors/instance data))))))

(t/deftest exception->error-data-ex-info-no-hint
  (t/testing "ex-info without :hint falls back to ex-message"
    (let [err  (ex-info "fallback message" {:type :validation})
          data (errors/exception->error-data err)]
      (t/is (= "fallback message" (:hint data))))))

;; Shared report-test helpers (copied from the governor suite so each
;; namespace stays self-contained).
(t/use-fixtures :each {:before #(errors/reset-report-governor!)})

(defn- error-cause
  [& {:keys [type code hint]}]
  (ex-info (or hint "boom")
           (cond-> {}
             (some? type) (assoc :type type)
             (some? code) (assoc :code code)
             (some? hint) (assoc :hint hint))))

(defn- capture-reports!
  "Run `f` capturing the events emitted through `st/emit!`."
  [f]
  (let [events (atom [])]
    (with-redefs [st/emit!            (mock/stub (fn [& emitted] (swap! events into emitted)))
                  rt/get-current-href (constantly "https://penpot.example.com/#/workspace")]
      (f)
      @events)))

(defn- report-events
  "The audit events (report emissions) out of everything collected through
  the `st/emit!` double. Toasts are ungoverned by design — one per `flash`
  call — so only the events typed as audit events count for emission
  bounds. Discriminates by `ptk/type`, which is total (never throws),
  because toasts are not derefable."
  [events]
  (filter #(= ::ev/event (ptk/type %)) events))

;; ---------------------------------------------------------------------------
;; Environment failures
;;
;; Connectivity and service failures are not application defects: they are
;; audit-only telemetry with a compact report and a dedicated toast.
;; ---------------------------------------------------------------------------

(t/deftest environment-error-classification
  (t/testing "environment failures are recognised"
    (doseq [type [:network :offline :bad-gateway :service-unavailable
                  :nitrate-unavailable :nitrate-not-configured]]
      (t/is (true? (errors/environment-error? (error-cause :type type)))
            (str "expected environment error: " type))))
  (t/testing "application defects are not environment failures"
    (doseq [type [:internal :assertion :persistence :validation :authentication]]
      (t/is (false? (errors/environment-error? (error-cause :type type)))
            (str "did not expect environment error: " type))))
  (t/testing "causes without ex-data are not environment failures"
    (t/is (false? (errors/environment-error? (js/Error. "plain failure"))))
    (t/is (false? (errors/environment-error? nil)))))

(t/deftest ^:async generate-report-compact-omits-stack-data-and-last-events
  (await
   (mock/with-mocks*
     {st/format-last-events (mock/stub (fn [& _] (throw (ex-info "must not be called" {}))))
      rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")}
     (let [cause  (ex-info "http error" {:type :offline
                                         :hint "http error"
                                         :uri "/api/rpc/command/update-file"
                                         :headers {"x-session-id" "secret"}})
           report (errors/generate-report cause {:format :compact})]
       (t/is (string? report))
       (t/is (str/includes? report "Hint:"))
       (t/is (str/includes? report "http error"))
       (t/is (str/includes? report ":offline"))
       (t/is (str/includes? report "/api/rpc/command/update-file"))
       (t/is (not (str/includes? report "Last events:")))
       (t/is (not (str/includes? report "Data:")))
       (t/is (not (str/includes? report "====")))
       (t/is (not (str/includes? report "secret")))
       ;; Trailing settle: the body is synchronous, but `with-mocks*`
       ;; evaluates to a promise, so the body must settle one.
       (await (async/settle))))))

(t/deftest ^:async generate-report-defaults-to-the-full-format
  (await
   (mock/with-mocks*
     {st/format-last-events (mock/stub (fn [& _] "(stub last events)"))
      rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")}
     (let [report (errors/generate-report (error-cause :type :internal :hint "boom"))]
       (t/is (str/includes? report "Last events:"))
       (t/is (str/includes? report "(stub last events)"))
       ;; Trailing settle: the body is synchronous, but `with-mocks*`
       ;; evaluates to a promise, so the body must settle one.
       (await (async/settle))))))

(t/deftest ^:async connectivity-handlers-report-governed-compact-audit-events
  ;; Scenario: a network failure and an offline status through the global
  ;; handler. Each is reported as a governed compact audit event plus its
  ;; connection toast. Proves: per type, report first and toast right after,
  ;; with no stack in the report and the dedicated toast message.
  (doseq [type [:network :offline]]
    (errors/reset-report-governor!)
    (let [events (atom [])
          cause  (ex-info "http error" {:type type :hint "http error"})]
      (await
       (mock/with-mocks*
         {st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
          rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
          tm/schedule           (mock/stub (fn [f] (f)))
          st/format-last-events (mock/stub (fn [& _] (throw (ex-info "must not be called" {}))))}
         (errors/on-error cause)
         (await (async/settle))
         ;; `flash` emits the report first and schedules the toast right after.
         (t/is (= 2 (count @events)) (str "unexpected event count for " type))
         (let [report-event (first @events)
               toast-event  (second @events)
               props        (deref report-event)]
           (t/is (= "handled-exception" (::ev/name props)))
           (t/is (not (str/includes? (:report props) "Last events:")))
           (t/is (= (i18n/tr "errors.connection-error")
                    (get-in (ptk/update toast-event {}) [:notification :content])))))))))

(t/deftest ^:async flash-keeps-the-canonical-event-name-and-derives-the-format
  ;; Scenario: an environment failure flashed as `:handled` and as
  ;; `:unhandled`. The audit event name is the canonical one requested by
  ;; the caller while only the payload format derives from the cause
  ;; (compact, with no stack and no header dump). Proves: per type, one
  ;; compact audit event under the requested name, with the `flash`
  ;; awaited to completion.
  (let [cause (ex-info "http error" {:type :network
                                     :hint "http error"
                                     :headers {"x-session-id" "secret"}})]
    (doseq [[type event-name] [[:handled "handled-exception"]
                               [:unhandled "unhandled-exception"]]]
      (errors/reset-report-governor!)
      (let [events (atom [])]
        (await
         (mock/with-mocks*
           {st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
            rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
            tm/schedule           (mock/stub (fn [f] (mock/asap f)))
            st/format-last-events (mock/stub (fn [& _] (throw (ex-info "must not be called" {}))))}
           ;; The event name is the canonical one requested by the caller;
           ;; only the payload format is derived from the cause.
           (await (errors/flash :cause cause :type type))
           (let [reports (report-events @events)]
             (t/is (= 1 (count reports)) (str "unexpected event count for " type))
             (let [props  (deref (first reports))
                   report (:report props)]
               (t/is (= event-name (::ev/name props)) (str "event name for " type))
               (t/is (not (str/includes? report "Last events:")))
               (t/is (not (str/includes? report "secret")))))))))))

(t/deftest ^:async offline-loop-is-governed-and-never-unhandled
  ;; Scenario: 10 000 offline errors through the global handler, replaying
  ;; a connectivity outage. Every occurrence is handled (never unhandled)
  ;; and the governor emits only the first. Proves: one `handled-exception`
  ;; audit event for the whole loop, observed after timers drain.
  (let [events (atom [])
        cause  (ex-info "http error" {:type :offline :hint "http error"})]
    (await
     (mock/with-mocks*
       {st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))
        st/format-last-events (mock/stub (fn [& _] (throw (ex-info "must not be called" {}))))}
       (dotimes [_ 10000]
         (errors/on-error cause))
       (await (async/settle))
       (t/is (= 1 (count (report-events @events))))
       (t/is (= "handled-exception" (::ev/name (deref (first (report-events @events))))))))))

(t/deftest ^:async flash-persistence-uses-compact-reports-for-environment-failures
  ;; Scenario: a save failure caused by the environment. `flash-persistence`
  ;; delegates to `flash`, so the audit event carries a compact report
  ;; (context only: no stack, no header dump). Proves: one
  ;; `handled-exception` with a compact payload, observed after timers
  ;; drain.
  (let [events (atom [])]
    (await
     (mock/with-mocks*
       {st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))
        st/format-last-events (mock/stub (fn [& _] (throw (ex-info "must not be called" {}))))}
       (errors/flash-persistence (ex-info "http error" {:type :offline
                                                        :hint "http error"
                                                        :headers {"x-session-id" "secret"}}))
       (await (async/settle))
       (let [reports (report-events @events)]
         (t/is (= 1 (count reports)))
         (let [props (deref (first reports))]
           (t/is (= "handled-exception" (::ev/name props)))
           (t/is (not (str/includes? (:report props) "Last events:")))
           (t/is (not (str/includes? (:report props) "secret")))))))))

(t/deftest exception-page-reports-dedup-by-cause
  (let [cause-a (error-cause :type :internal :code :unable-to-process-repository-response :hint "boom")
        cause-b (error-cause :type :internal :code :other :hint "other")
        events  (capture-reports!
                 (fn []
                   (errors/submit-report :event-name "exception-page"
                                         :report "report" :hint "boom" :cause cause-a)
                   (errors/submit-report :event-name "exception-page"
                                         :report "report" :hint "different hint" :cause cause-a)
                   (errors/submit-report :event-name "exception-page"
                                         :report "report" :hint "other" :cause cause-b)))]
    (t/is (= 2 (count events)))))

(t/deftest reports-of-the-same-cause-under-different-names-do-not-coalesce
  (let [cause  (error-cause :type :internal :hint "boom")
        events (capture-reports!
                (fn []
                  (errors/submit-report :event-name "handled-exception"
                                        :report "report" :hint "boom" :cause cause)
                  (errors/submit-report :event-name "unhandled-exception"
                                        :report "report" :hint "boom" :cause cause)
                  (errors/submit-report :event-name "exception-page"
                                        :report "report" :hint "boom" :cause cause)))]
    (t/is (= 3 (count events)))))

;; ---------------------------------------------------------------------------
;; on-error dispatches to ptk/handle-error
;;
;; We use a dedicated test-only error type so we can add/remove a
;; defmethod without touching the real handlers.
;; ---------------------------------------------------------------------------

(def ^:private test-handled (atom nil))

(defmethod ptk/handle-error ::test-dispatch
  [err]
  (reset! test-handled err))

(t/deftest on-error-dispatches-map-error
  (t/testing "on-error dispatches a map error to ptk/handle-error using its :type"
    (reset! test-handled nil)
    (errors/on-error {:type ::test-dispatch :hint "hello"})
    (t/is (= ::test-dispatch (:type @test-handled)))
    (t/is (= "hello" (:hint @test-handled)))))

(t/deftest on-error-wraps-exception-then-dispatches
  (t/testing "on-error wraps a JS Error into error-data before dispatching"
    (reset! test-handled nil)
    (let [err (ex-info "wrapped" {:type ::test-dispatch})]
      (errors/on-error err)
      (t/is (= ::test-dispatch (:type @test-handled)))
      (t/is (identical? err (::errors/instance @test-handled))))))

;; ---------------------------------------------------------------------------
;; on-error re-entrancy guard
;;
;; The guard is implemented via the `handling-error?` volatile inside
;; app.main.errors.  We can verify its effect by registering a
;; handle-error method that itself calls on-error and checking that
;; only one invocation gets through.
;; ---------------------------------------------------------------------------

(def ^:private reentrant-call-count (atom 0))

(defmethod ptk/handle-error ::test-reentrant
  [_err]
  (swap! reentrant-call-count inc)
  ;; Simulate a secondary error inside the error handler
  ;; (e.g. the notification emit itself throws).
  ;; Without the re-entrancy guard this would recurse indefinitely.
  (when (= 1 @reentrant-call-count)
    (errors/on-error (ex-info "test" {:type ::test-reentrant :hint "secondary"}))))

(t/deftest on-error-reentrancy-guard-prevents-recursion
  (t/testing "a second on-error call while handling an error is suppressed by the guard"
    (reset! reentrant-call-count 0)
    (errors/on-error (ex-info "test" {:type ::test-reentrant :hint "first"}))
    ;; The guard must have allowed only the first invocation through.
    (t/is (= 1 @reentrant-call-count))))

;; ---------------------------------------------------------------------------
;; Expired organization SSO session
;;
;; The backend rejects SSO-guarded requests with an :authentication error
;; coded :nitrate-sso-required once the organization SSO session lapses.
;; The user must be sent back through the identity provider instead of
;; being told they have no access to the file.
;; ---------------------------------------------------------------------------

(def ^:private workspace-href
  "https://penpot.example.com/#/workspace?team-id=b8f8bb52-8b70-8144-8004-4a5085f0bdc9")

(def ^:private organization-id "d1a4c0f2-2f36-8114-8006-1b0e6d9d0c11")

(defn- sso-required-error
  []
  {:type :authentication
   :code :nitrate-sso-required
   :organization-id organization-id
   :team-id "b8f8bb52-8b70-8144-8004-4a5085f0bdc9"})

(t/deftest ^:async expired-organization-sso-navigates-to-identity-provider
  (t/testing "the browser is sent to the identity provider instead of an error page"
    (let [events (atom [])]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub
                            (fn [_command _params]
                              (rx/of {:authorized false
                                      :redirect-uri "https://idp.example.com/authorize"})))
          rt/get-current-href (constantly workspace-href)
          st/emit!         (mock/stub (fn [& emitted] (swap! events into emitted)))}
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= [::rt/nav-raw] (mapv ptk/type @events))))))))

(t/deftest ^:async expired-organization-sso-comes-back-to-the-current-location
  (t/testing "the SSO check asks the provider to return the user where they were"
    (let [rpc-calls (atom [])]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub
                            (fn [command params]
                              (swap! rpc-calls conj {:command command :params params})
                              (rx/of {:authorized false
                                      :redirect-uri "https://idp.example.com/authorize"})))
          rt/get-current-href (constantly workspace-href)
          st/emit!         mock/noop}
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= [{:command :check-nitrate-sso
                    :params {:team-id "b8f8bb52-8b70-8144-8004-4a5085f0bdc9"
                             :organization-id organization-id
                             :url workspace-href}}]
                  @rpc-calls)))))))

(t/deftest ^:async already-satisfied-organization-sso-retries-the-location
  (t/testing "a session renewed meanwhile (e.g. in another tab) reloads instead of erroring"
    (let [events (atom [])]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub
                            (fn [_command _params]
                              (rx/of {:authorized true :reason :sso-satisfied})))
          rt/get-current-href (constantly workspace-href)
          st/emit!         (mock/stub (fn [& emitted] (swap! events into emitted)))}
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= [::rt/reload] (mapv ptk/type @events))))))))

(t/deftest ^:async organization-sso-without-usable-provider-shows-the-sso-error-dialog
  (t/testing "SSO is required but there is nowhere to go: offer a retry, not a permission error"
    (let [assigned* (atom nil)]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub
                            (fn [_command _params]
                              (rx/of {:authorized false :redirect-uri nil})))
          rt/get-current-href (constantly workspace-href)
          rt/assign-exception (fn [error]
                                (reset! assigned* error)
                                (ptk/data-event ::assigned error))}
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= :sso-error (:type @assigned*)))
         (t/is (= organization-id (:organization-id @assigned*)))
         (t/is (true? (:is-workspace @assigned*))))))))

(t/deftest ^:async organization-sso-without-team-access-reports-a-permission-failure
  (t/testing "a user who cannot reach the team keeps getting the authentication error"
    (let [assigned* (atom nil)]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub
                            (fn [_command _params]
                              (rx/of {:authorized true :reason :no-team-access})))
          rt/get-current-href (constantly workspace-href)
          rt/assign-exception (fn [error]
                                (reset! assigned* error)
                                (ptk/data-event ::assigned error))}
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= :authentication (:type @assigned*)))
         (t/is (= :nitrate-sso-required (:code @assigned*))))))))

(t/deftest ^:async organization-sso-does-not-retry-on-an-unexplained-authorization
  (t/testing "reloading on an answer we don't understand would spin on the same rejection"
    (let [events (atom [])]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub (fn [_command _params] (rx/of {:authorized true})))
          rt/get-current-href (constantly workspace-href)
          rt/assign-exception (fn [error] (ptk/data-event ::assigned error))
          st/async-emit!   (fn [& emitted] (swap! events into emitted))}
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= [::assigned] (mapv ptk/type @events))))))))

(t/deftest ^:async organization-sso-error-without-context-is-reported-as-it-arrives
  (t/testing "with no organization and no team there is nothing to check"
    (let [rpc-calls (atom 0)
          assigned* (atom nil)]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub (fn [_command _params]
                                        (swap! rpc-calls inc)
                                        (rx/empty)))
          rt/get-current-href (constantly workspace-href)
          rt/assign-exception (fn [error]
                                (reset! assigned* error)
                                (ptk/data-event ::assigned error))}
         (errors/on-error {:type :authentication
                           :code :nitrate-sso-required})
         (await (async/settle))
         (t/is (zero? @rpc-calls))
         (t/is (= :nitrate-sso-required (:code @assigned*))))))))

(t/deftest ^:async a-resultless-organization-sso-check-does-not-wedge-later-rejections
  (t/testing "the one-in-flight guard is released even when no answer arrives"
    (let [rpc-calls (atom 0)]
      (await
       (mock/with-mocks*
         {rp/cmd!          (mock/stub (fn [_command _params]
                                        (swap! rpc-calls inc)
                                        (rx/empty)))
          rt/get-current-href (constantly workspace-href)
          st/emit!         mock/noop}
         (errors/on-error (sso-required-error))
         (errors/on-error (sso-required-error))
         (await (async/settle))
         (t/is (= 2 @rpc-calls)))))))

;; A failing check must stay a failing check: the generic handling turns it
;; into a toast, whereas swallowing it would show a permission error for
;; what may be a momentary network blip. The mocked RPC fails on a later
;; tick, like a real request, so the handler is not inside on-error's
;; re-entrancy guard when the failure arrives.

(def ^:private check-failures (atom []))

(defmethod ptk/handle-error ::test-check-failure
  [error]
  (swap! check-failures conj error))

(t/deftest ^:async failing-organization-sso-check-is-not-reported-as-missing-access
  (t/testing "the SSO check fails like a real request (later tick) and the failure stays a failure"
    (reset! check-failures [])
    (let [assigned* (atom nil)]
      (await
       (mock/with-mocks*
         {rp/cmd!
          (mock/stub
           (fn [_command _params]
             (->> (rx/timer 0)
                  (rx/mapcat (fn [_]
                               (rx/throw (ex-info "boom" {:type ::test-check-failure})))))))

          rt/get-current-href
          (constantly workspace-href)

          rt/assign-exception
          (fn [error]
            (reset! assigned* error)
            (ptk/data-event ::assigned error))}
         (errors/on-error (sso-required-error))
         (await (async/wait-for #(= 1 (count @check-failures))
                                "sso check failure observed"))
         (t/is (= [::test-check-failure] (mapv :type @check-failures)))
         (t/is (nil? @assigned*)))))))

;; ---------------------------------------------------------------------------
;; :validation / :invalid-sso-config
;;
;; The SSO error page needs an organization-id to retry meaningfully. Promote
;; to :sso-error only when that id is present; otherwise keep :validation so
;; we do not surface a broken SSO dialog for a future code path that omits it.
;; ---------------------------------------------------------------------------

(defn- capture-async-exception
  "Invoke `ptk/handle-error` while capturing the error map passed to
  `rt/assign-exception` via `st/async-emit!`.

  `st/async-emit!` is variadic (`[& params]`); the mock must be too,
  otherwise CLJS looks up `IFn$_invoke$arity$variadic` and throws."
  [error]
  (let [captured (atom nil)]
    (with-redefs [st/async-emit!      (fn [& events]
                                        (reset! captured (first events)))
                  rt/assign-exception (fn [err] err)]
      (ptk/handle-error error)
      @captured)))

(t/deftest invalid-sso-config-with-organization-id-promotes-to-sso-error
  (t/testing "invalid-sso-config with :organization-id is shown as :sso-error"
    (let [org-id #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
          assigned (capture-async-exception
                    {:type :validation
                     :code :invalid-sso-config
                     :organization-id org-id
                     :hint "missing issuer"})]
      (t/is (= :sso-error (:type assigned)))
      (t/is (= org-id (:organization-id assigned)))
      (t/is (= :invalid-sso-config (:code assigned))))))

(t/deftest invalid-sso-config-without-organization-id-keeps-validation
  (t/testing "invalid-sso-config without :organization-id must not become :sso-error"
    (let [assigned (capture-async-exception
                    {:type :validation
                     :code :invalid-sso-config
                     :hint "missing issuer"})]
      (t/is (= :validation (:type assigned)))
      (t/is (nil? (:organization-id assigned)))
      (t/is (= :invalid-sso-config (:code assigned))))))

(t/deftest persistence-notifications-do-not-expire-but-other-flashes-do
  (doseq [[notify timeout] [[#(errors/flash :hint "Ordinary error") 5000]
                            [#(errors/flash :hint "Custom error" :timeout 1000) 1000]
                            [#(errors/flash-persistence nil) nil]]]
    (let [scheduled (atom [])
          events    (atom [])]
      (with-redefs [tm/schedule (mock/stub #(swap! scheduled conj %))
                    st/emit! (mock/stub (fn [& emitted] (swap! events into emitted)))]
        (notify)
        (t/is (empty? @events) "Keep notification delivery asynchronous")
        (doseq [callback @scheduled] (callback))
        (t/is (= 1 (count @events)))
        (let [state (ptk/update (first @events) {})]
          (t/is (= timeout (get-in state [:notification :timeout])))
          (t/is (= :visible (get-in state [:notification :status]))))))))

(t/deftest ^:async persistence-notifications-include-an-error-report-download
  (let [scheduled      (atom [])
        idle-callbacks (atom [])
        events         (atom [])
        downloads      (atom [])
        revoked        (atom [])
        report         "generated error report"
        cause          (ex-info "Save failed" {:type :validation})]
    (await
     (mock/with-mocks*
       {dom/prevent-default         (fn [_])
        dom/trigger-download-uri    (fn [& params]
                                      (swap! downloads conj params))
        errors/generate-report      (fn [_ & _] report)
        errors/submit-report        (fn [& _])
        ;; `tr` is called with one and with two arguments, and its
        ;; two-argument arity is variadic: the stub exposes both
        ;; shapes so the compiled static calls resolve.
        i18n/tr                     (fn ([key] (str key ":"))
                                      ([key & args]
                                       (str key ":" (first args))))
        st/emit!                    (mock/stub (fn [& emitted]
                                                 (swap! events into emitted)))
        tm/schedule                 (mock/stub (fn [callback]
                                                 (swap! scheduled conj callback)))
        tm/schedule-on-idle         (mock/stub (fn [callback]
                                                 (swap! idle-callbacks conj callback)))
        wapi/create-blob            (mock/stub (fn [content media-type]
                                                 {:content content :media-type media-type}))
        wapi/create-uri             (fn [_] "blob:report")
        wapi/revoke-uri             (fn [uri]
                                      (swap! revoked conj uri))}
       (errors/flash-persistence cause)
       (doseq [callback @scheduled] (callback))
       (await (async/settle))
       ;; The report is emitted first and the toast right after, so the
       ;; toast carrying the download link is the last event collected.
       (let [state    (ptk/update (last @events) {})
             download (get-in state [:notification :links 0])]
         (t/is (= "labels.download:report.txt" (:label download)))
         ((:callback download) nil)
         (t/is (= [["report" "text/plain" "blob:report"]] @downloads))
         (doseq [callback @idle-callbacks] (callback))
         (t/is (= ["blob:report"] @revoked)))))))

(t/deftest ^:async persistence-waiters-do-not-report-an-already-handled-failure
  (let [reports  (atom [])
        rejected (atom [])
        pstate   (atom {:status :saving})
        store    (ptk/store {:state {} :on-error errors/on-error})
        cause    (ex-info "Save failed" {:type :network})]
    (await
     (mock/with-mocks*
       {refs/persistence pstate
        st/emit!         (mock/stub (fn [& emitted]
                                      (swap! reports into
                                             (report-events emitted))))
        ;; Run the scheduled `flash` body synchronously: the whole flow
        ;; under test is synchronous except for the deferral.
        tm/schedule      (mock/stub (fn [f] (f)))}
       (try
         (ptk/emit! store (#'dps/persistence-failed (uuid/next) cause))
         (reset! pstate (:persistence @store))
         (dotimes [_ 2]
           (->> (dps/wait-persisted-or-error)
                (rx/subs! (fn [_] (t/is false "A failed save must still reject"))
                          (fn [error]
                            (swap! rejected conj error)
                            (errors/on-error error)))))
         (await (async/settle))
         (t/is (= 2 (count @rejected)))
         (t/is (= 1 (count @reports)))
         ;; A standalone timeout and a later save failure are new incidents.
         ;; The later failure carries a distinct signature: repeating the same
         ;; one inside the governor window is coalesced by design.
         (errors/on-error (ex-info "Save timed out" {:type :persistence :code :save-timeout}))
         (await (async/settle))
         (t/is (= 2 (count @reports)))
         (ptk/emit! store (#'dps/persistence-failed (uuid/next)
                                                    (ex-info "Save failed again" {:type :network})))
         (await (async/settle))
         (t/is (= 3 (count @reports)))
         (finally
           (rx/dispose! store)))))))

;; ---------------------------------------------------------------------------
;; Save failures that belong to their own handler
;;
;; Some causes are not resolved by retaining the changes: the session has to
;; be renewed, the file is gone, or a different version was restored.  Each
;; one is handled by the error handler for its own type.
;; ---------------------------------------------------------------------------

(t/deftest expired-session-during-save-goes-to-the-authentication-handler
  (t/testing "a lost session is handled as an authentication error, not notified"
    (let [assigned  (atom nil)
          scheduled (atom [])]
      (with-redefs [rt/get-current-href (constantly workspace-href)
                    rt/assign-exception (fn [error] error)
                    st/async-emit!      (fn [& events] (reset! assigned (first events)))
                    tm/schedule         (mock/stub (fn [callback]
                                                     (swap! scheduled conj callback)))]
        (errors/flash-persistence (ex-info "Session expired" {:type :authentication}))
        (t/is (= :authentication (:type @assigned)))
        (t/is (empty? @scheduled) "An expired session shows no save notification")))))

(t/deftest ^:async expired-organization-sso-during-save-renews-the-session
  (t/testing "an SSO-guarded save failure goes back through the identity provider"
    (let [events (atom [])]
      (await
       (mock/with-mocks*
         {rp/cmd!             (mock/stub
                               (fn [_command _params]
                                 (rx/of {:authorized false
                                         :redirect-uri "https://idp.example.com/authorize"})))
          rt/get-current-href (constantly workspace-href)
          st/emit!            (mock/stub (fn [& emitted] (swap! events into emitted)))}
         (errors/flash-persistence (ex-info "SSO required" (sso-required-error)))
         (await (async/settle))
         (t/is (= [::rt/nav-raw] (mapv ptk/type @events))))))))

(t/deftest a-deleted-file-during-save-shows-the-exception-page
  (t/testing "a file that no longer exists shows its page, not the save notification"
    (let [events    (atom [])
          scheduled (atom [])]
      (with-redefs [rt/assign-exception (fn [error] error)
                    st/emit!            (mock/stub (fn [& emitted]
                                                     (swap! events into emitted)))
                    tm/schedule         (mock/stub (fn [callback]
                                                     (swap! scheduled conj callback)))]
        (errors/flash-persistence (ex-info "File not found" {:type :not-found}))
        (doseq [callback @scheduled] (callback))
        (t/is (= [:not-found] (mapv :type @events)))))))

(t/deftest a-restored-version-during-save-reloads-the-file
  (t/testing "a version restored elsewhere reloads the file instead of notifying"
    (let [events    (atom [])
          scheduled (atom [])]
      (with-redefs [st/emit!    (mock/stub (fn [& emitted] (swap! events into emitted)))
                    tm/schedule (mock/stub (fn [callback]
                                             (swap! scheduled conj callback)))]
        (errors/flash-persistence (ex-info "A different version has been restored"
                                           {:type :validation :code :vern-conflict}))
        (t/is (= [::dw/reload-current-file] (mapv ptk/type @events)))
        (t/is (empty? @scheduled) "A restored version shows no save notification")))))

(t/deftest ^:async other-validation-failures-during-save-keep-the-notification
  (t/testing "a validation failure without a recovery of its own is still notified"
    (let [events    (atom [])
          scheduled (atom [])]
      (await
       (mock/with-mocks*
         {errors/generate-report (fn [_ & _] "generated error report")
          errors/submit-report   (fn [& _])
          st/emit!               (mock/stub (fn [& emitted]
                                              (swap! events into emitted)))
          tm/schedule            (mock/stub (fn [callback]
                                              (swap! scheduled conj callback)))}
         (errors/flash-persistence (ex-info "Invalid data" {:type :validation
                                                            :code :invalid-data}))
         (doseq [callback @scheduled] (callback))
         (await (async/settle))
         ;; The report is emitted first and the toast right after: one
         ;; audit event plus the sticky toast carrying the report link.
         (t/is (= 1 (count (report-events @events))))
         (let [state (ptk/update (last @events) {})]
           (t/is (nil? (get-in state [:notification :timeout])))
           (t/is (some? (get-in state [:notification :links 0])))))))))
