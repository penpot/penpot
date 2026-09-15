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
    - flash schedules async emit  – ntf/show is not emitted synchronously
    - organization SSO recovery   – expired SSO sessions go back to the provider
    - invalid-sso-config handler  – requires :organization-id to promote to :sso-error"
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.persistence :as dps]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
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

;; ---------------------------------------------------------------------------
;; Error report governor
;;
;; The governor deduplicates report by fingerprint: the first occurrence is
;; always emitted, repeats inside a 2 minute window are counted, and the
;; fingerprint cache is bounded (the oldest entry is evicted when full).
;; ---------------------------------------------------------------------------

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

(t/deftest fingerprint-is-stable-for-equivalent-errors
  (let [cause-a (error-cause :type :network :code :fetch-failed :hint "unable to perform fetch operation")
        cause-b (error-cause :type :network :code :fetch-failed :hint "unable to perform fetch operation")]
    (t/is (= (errors/error-fingerprint "handled-exception" cause-a)
             (errors/error-fingerprint "handled-exception" cause-b)))))

(t/deftest fingerprint-changes-with-error-identity
  (let [base (error-cause :type :network :code :fetch-failed :hint "boom")]
    (t/is (not= (errors/error-fingerprint "handled-exception" base)
                (errors/error-fingerprint "handled-exception"
                                          (error-cause :type :network :code :fetch-failed :hint "other"))))
    (t/is (not= (errors/error-fingerprint "handled-exception" base)
                (errors/error-fingerprint "handled-exception"
                                          (error-cause :type :validation :code :fetch-failed :hint "boom"))))
    (t/is (not= (errors/error-fingerprint "handled-exception" base)
                (errors/error-fingerprint "handled-exception"
                                          (error-cause :type :network :code :other :hint "boom"))))))

(t/deftest fingerprint-includes-the-report-name
  (let [cause (error-cause :type :network :hint "boom")]
    (t/is (not= (errors/error-fingerprint "handled-exception" cause)
                (errors/error-fingerprint "unhandled-exception" cause)))
    (t/is (not= (errors/error-fingerprint "handled-exception" cause)
                (errors/error-fingerprint "exception-page" cause)))))

(t/deftest fingerprint-handles-missing-type-and-code
  (let [fingerprint (errors/error-fingerprint "handled-exception" (js/Error. "plain failure"))]
    (t/is (string? fingerprint))
    (t/is (str/starts-with? fingerprint "handled-exception|unknown|unknown|"))))

(t/deftest fallback-fingerprint-is-stable-and-discriminating
  (t/is (= (errors/fallback-fingerprint "exception-page" "boom")
           (errors/fallback-fingerprint "exception-page" "boom")))
  (t/is (not= (errors/fallback-fingerprint "exception-page" "boom")
              (errors/fallback-fingerprint "handled-exception" "boom")))
  (t/is (not= (errors/fallback-fingerprint "exception-page" "boom")
              (errors/fallback-fingerprint "exception-page" "other"))))

(t/deftest governor-emits-first-occurrence-and-suppresses-repeats
  (let [d1 (errors/reserve-report* (errors/initial-report-state) "fp" 1000)
        d2 (errors/reserve-report* d1 "fp" 2000)
        d3 (errors/reserve-report* d2 "fp" 3000)
        d4 (errors/reserve-report* d3 "fp" (+ 1000 errors/report-window-ms))]
    (t/is (true? (::errors/emit d1)))
    (t/is (= 1 (::errors/occurrences d1)))
    (t/is (false? (::errors/emit d2)))
    (t/is (nil? (::errors/occurrences d2)))
    (t/is (false? (::errors/emit d3)))
    (t/is (nil? (::errors/occurrences d3)))
    (t/is (true? (::errors/emit d4)))
    (t/is (= 3 (::errors/occurrences d4)))))

(t/deftest governor-evicts-oldest-entry-when-cache-is-full
  (let [base  (reduce (fn [state i]
                        (errors/reserve-report* state (str "fp-" i) (* 1000 i)))
                      (errors/initial-report-state)
                      (range errors/max-tracked-fingerprints))
        state (errors/reserve-report* base
                                      "fp-new"
                                      (* 1000 errors/max-tracked-fingerprints))]
    (t/is (= errors/max-tracked-fingerprints (count (:entries state))))
    (t/is (= errors/max-tracked-fingerprints (count (:order state))))
    (t/is (true? (::errors/emit state)))
    (t/is (nil? (get-in state [:entries "fp-0"])))
    (t/is (= "fp-1" (peek (:order state))))
    (t/is (some? (get-in state [:entries "fp-new"])))))

(t/deftest governor-evicts-by-insertion-order-not-by-last-emission
  (let [base       (reduce (fn [state i]
                             (errors/reserve-report* state (str "fp-" i) (* 1000 i)))
                           (errors/initial-report-state)
                           (range errors/max-tracked-fingerprints))
        ;; fp-0 re-emits after the window, so its :emitted-at becomes the
        ;; most recent one, but it keeps its insertion position.
        re-emitted  (errors/reserve-report* base
                                            "fp-0"
                                            (+ (* 1000 errors/max-tracked-fingerprints)
                                               errors/report-window-ms))
        state       (errors/reserve-report* re-emitted
                                            "fp-new"
                                            (+ (* 1000 errors/max-tracked-fingerprints)
                                               errors/report-window-ms
                                               1000))]
    (t/is (true? (::errors/emit state)))
    ;; FIFO: the first inserted one goes, even though it was the last
    ;; emitted and fp-1 is the oldest by :emitted-at.
    (t/is (nil? (get-in state [:entries "fp-0"])))
    (t/is (some? (get-in state [:entries "fp-1"])))
    (t/is (= errors/max-tracked-fingerprints (count (:entries state))))
    (t/is (= errors/max-tracked-fingerprints (count (:order state))))))

(t/deftest submit-report-is-governed-and-reports-occurrences
  (let [cause  (error-cause :type :network :code :fetch-failed :hint "boom")
        events (capture-reports!
                (fn []
                  (dotimes [_ 5]
                    (errors/submit-report :event-name "handled-exception"
                                          :report "report"
                                          :hint "boom"
                                          :cause cause))))]
    (t/is (= 1 (count events)))
    (t/is (= 1 (:occurrences (deref (first events)))))))

(t/deftest invalid-report-does-not-consume-a-reservation
  (let [cause  (error-cause :type :network :hint "boom")
        events (capture-reports!
                (fn []
                  (errors/submit-report :event-name "handled-exception"
                                        :report nil :hint "boom" :cause cause)
                  (errors/submit-report :event-name "handled-exception"
                                        :report "report" :hint "boom" :cause cause)))]
    (t/is (= 1 (count events)))
    (t/is (= 1 (:occurrences (deref (first events)))))))

(t/deftest governor-applies-to-every-report-name
  (let [events (capture-reports!
                (fn []
                  (doseq [event-name ["handled-exception" "unhandled-exception" "exception-page"]]
                    (errors/submit-report :event-name event-name :report "report" :hint event-name)
                    (errors/submit-report :event-name event-name :report "report" :hint event-name))))]
    (t/is (= 3 (count events)))))

(t/deftest submit-report-without-cause-dedups-by-fallback-fingerprint
  (let [events (capture-reports!
                (fn []
                  (errors/submit-report :event-name "exception-page" :report "report" :hint "boom")
                  (errors/submit-report :event-name "exception-page" :report "report" :hint "boom")
                  (errors/submit-report :event-name "exception-page" :report "report" :hint "other")))]
    (t/is (= 2 (count events)))))

(t/deftest governor-bounds-an-incident-like-loop
  (let [cause  (error-cause :type :network :hint "unable to perform fetch operation")
        events (capture-reports!
                (fn []
                  (dotimes [_ 10000]
                    (errors/submit-report :event-name "handled-exception"
                                          :report "report"
                                          :hint "unable to perform fetch operation"
                                          :cause cause))))]
    (t/is (= 1 (count events)))))

(t/deftest flash-suppressed-occurrence-does-not-build-a-report
  (let [generated (atom 0)
        cause     (error-cause :type :network :hint "unable to perform fetch operation")
        events    (atom [])]
    (with-redefs [errors/generate-report (fn [_] (swap! generated inc) "report")
                  st/emit!               (mock/stub (fn [& emitted] (swap! events into emitted)))
                  rt/get-current-href    (constantly "https://penpot.example.com/#/workspace")
                  tm/schedule            mock/noop]
      (dotimes [_ 3]
        (errors/flash :cause cause :type :handled))
      (t/is (= 1 (count @events)))
      (t/is (= 1 @generated)))))

(t/deftest flash-bounds-an-incident-like-loop
  (let [generated (atom 0)
        cause     (error-cause :type :network :hint "unable to perform fetch operation")
        events    (atom [])]
    (with-redefs [errors/generate-report (fn [_] (swap! generated inc) "report")
                  st/emit!               (mock/stub (fn [& emitted] (swap! events into emitted)))
                  rt/get-current-href    (constantly "https://penpot.example.com/#/workspace")
                  tm/schedule            mock/noop]
      (dotimes [_ 10000]
        (errors/flash :cause cause :type :handled))
      (t/is (= 1 (count @events)))
      (t/is (= 1 @generated)))))

(t/deftest generate-report-is-total-when-formatting-fails
  (with-redefs [st/format-last-events (mock/stub (fn [& _] (throw (ex-info "formatting failed" {}))))]
    (let [report (errors/generate-report (error-cause :type :network :hint "boom"))]
      (t/is (string? report)))))

(t/deftest flash-emits-a-fallback-report-when-generation-fails
  (let [events (atom [])]
    (with-redefs [st/format-last-events (mock/stub (fn [& _] (throw (ex-info "formatting failed" {}))))
                  st/emit!               (mock/stub (fn [& emitted] (swap! events into emitted)))
                  rt/get-current-href    (constantly "https://penpot.example.com/#/workspace")
                  tm/schedule            mock/noop]
      (errors/flash :cause (error-cause :type :network :hint "boom") :type :handled)
      (t/is (= 1 (count @events)))
      (t/is (string? (:report (deref (first @events))))))))

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

(t/deftest expired-organization-sso-navigates-to-identity-provider
  (t/async done
    (t/testing "the browser is sent to the identity provider instead of an error page"
      (let [events (atom [])]
        (mock/with-mocks
          {rp/cmd!          (mock/stub
                             (fn [_command _params]
                               (rx/of {:authorized false
                                       :redirect-uri "https://idp.example.com/authorize"})))
           rt/get-current-href (constantly workspace-href)
           st/emit!         (mock/stub (fn [& emitted] (swap! events into emitted)))}
          (fn [done']
            (errors/on-error (sso-required-error))
            (t/is (= [::rt/nav-raw] (mapv ptk/type @events)))
            (done'))
          done)))))

(t/deftest expired-organization-sso-comes-back-to-the-current-location
  (t/async done
    (t/testing "the SSO check asks the provider to return the user where they were"
      (let [rpc-calls (atom [])]
        (mock/with-mocks
          {rp/cmd!          (mock/stub
                             (fn [command params]
                               (swap! rpc-calls conj {:command command :params params})
                               (rx/of {:authorized false
                                       :redirect-uri "https://idp.example.com/authorize"})))
           rt/get-current-href (constantly workspace-href)
           st/emit!         mock/noop}
          (fn [done']
            (errors/on-error (sso-required-error))
            (t/is (= [{:command :check-nitrate-sso
                       :params {:team-id "b8f8bb52-8b70-8144-8004-4a5085f0bdc9"
                                :organization-id organization-id
                                :url workspace-href}}]
                     @rpc-calls))
            (done'))
          done)))))

(t/deftest already-satisfied-organization-sso-retries-the-location
  (t/async done
    (t/testing "a session renewed meanwhile (e.g. in another tab) reloads instead of erroring"
      (let [events (atom [])]
        (mock/with-mocks
          {rp/cmd!          (mock/stub
                             (fn [_command _params]
                               (rx/of {:authorized true :reason :sso-satisfied})))
           rt/get-current-href (constantly workspace-href)
           st/emit!         (mock/stub (fn [& emitted] (swap! events into emitted)))}
          (fn [done']
            (errors/on-error (sso-required-error))
            (t/is (= [::rt/reload] (mapv ptk/type @events)))
            (done'))
          done)))))

(t/deftest organization-sso-without-usable-provider-shows-the-sso-error-dialog
  (t/async done
    (t/testing "SSO is required but there is nowhere to go: offer a retry, not a permission error"
      (let [assigned* (atom nil)]
        (mock/with-mocks
          {rp/cmd!          (mock/stub
                             (fn [_command _params]
                               (rx/of {:authorized false :redirect-uri nil})))
           rt/get-current-href (constantly workspace-href)
           rt/assign-exception (fn [error]
                                 (reset! assigned* error)
                                 (ptk/data-event ::assigned error))}
          (fn [done']
            (errors/on-error (sso-required-error))
            (t/is (= :sso-error (:type @assigned*)))
            (t/is (= organization-id (:organization-id @assigned*)))
            (t/is (true? (:is-workspace @assigned*)))
            (done'))
          done)))))

(t/deftest organization-sso-without-team-access-reports-a-permission-failure
  (t/async done
    (t/testing "a user who cannot reach the team keeps getting the authentication error"
      (let [assigned* (atom nil)]
        (mock/with-mocks
          {rp/cmd!          (mock/stub
                             (fn [_command _params]
                               (rx/of {:authorized true :reason :no-team-access})))
           rt/get-current-href (constantly workspace-href)
           rt/assign-exception (fn [error]
                                 (reset! assigned* error)
                                 (ptk/data-event ::assigned error))}
          (fn [done']
            (errors/on-error (sso-required-error))
            (t/is (= :authentication (:type @assigned*)))
            (t/is (= :nitrate-sso-required (:code @assigned*)))
            (done'))
          done)))))

(t/deftest organization-sso-does-not-retry-on-an-unexplained-authorization
  (t/async done
    (t/testing "reloading on an answer we don't understand would spin on the same rejection"
      (let [events (atom [])]
        (mock/with-mocks
          {rp/cmd!          (mock/stub (fn [_command _params] (rx/of {:authorized true})))
           rt/get-current-href (constantly workspace-href)
           rt/assign-exception (fn [error] (ptk/data-event ::assigned error))
           st/async-emit!   (fn [& emitted] (swap! events into emitted))}
          (fn [done']
            (errors/on-error (sso-required-error))
            (t/is (= [::assigned] (mapv ptk/type @events)))
            (done'))
          done)))))

(t/deftest organization-sso-error-without-context-is-reported-as-it-arrives
  (t/async done
    (t/testing "with no organization and no team there is nothing to check"
      (let [rpc-calls (atom 0)
            assigned* (atom nil)]
        (mock/with-mocks
          {rp/cmd!          (mock/stub (fn [_command _params]
                                         (swap! rpc-calls inc)
                                         (rx/empty)))
           rt/get-current-href (constantly workspace-href)
           rt/assign-exception (fn [error]
                                 (reset! assigned* error)
                                 (ptk/data-event ::assigned error))}
          (fn [done']
            (errors/on-error {:type :authentication
                              :code :nitrate-sso-required})
            (t/is (zero? @rpc-calls))
            (t/is (= :nitrate-sso-required (:code @assigned*)))
            (done'))
          done)))))

(t/deftest a-resultless-organization-sso-check-does-not-wedge-later-rejections
  (t/async done
    (t/testing "the one-in-flight guard is released even when no answer arrives"
      (let [rpc-calls (atom 0)]
        (mock/with-mocks
          {rp/cmd!          (mock/stub (fn [_command _params]
                                         (swap! rpc-calls inc)
                                         (rx/empty)))
           rt/get-current-href (constantly workspace-href)
           st/emit!         mock/noop}
          (fn [done']
            (errors/on-error (sso-required-error))
            (errors/on-error (sso-required-error))
            (t/is (= 2 @rpc-calls))
            (done'))
          done)))))

;; A failing check must stay a failing check: the generic handling turns it
;; into a toast, whereas swallowing it would show a permission error for
;; what may be a momentary network blip. The mocked RPC fails on a later
;; tick, like a real request, so the handler is not inside on-error's
;; re-entrancy guard when the failure arrives.

(def ^:private check-failures (atom []))

(defmethod ptk/handle-error ::test-check-failure
  [error]
  (swap! check-failures conj error))

(t/deftest failing-organization-sso-check-is-not-reported-as-missing-access
  (t/async done
    (reset! check-failures [])
    (let [assigned* (atom nil)]
      (mock/with-mocks
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

        (fn [done']
          (errors/on-error (sso-required-error))
          (tm/schedule
           50
           (fn []
             (t/is (= [::test-check-failure] (mapv :type @check-failures)))
             (t/is (nil? @assigned*))
             (done'))))
        done))))

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

(t/deftest persistence-waiters-do-not-report-an-already-handled-failure
  (let [reports  (atom [])
        rejected (atom [])
        pstate   (atom {:status :saving})
        store    (ptk/store {:state {} :on-error errors/on-error})
        cause    (ex-info "Save failed" {:type :network})]
    (with-redefs [refs/persistence pstate
                  st/emit!         (mock/stub (fn [& emitted] (swap! reports into emitted)))
                  tm/schedule      (mock/stub (fn [_]))]
      (try
        (ptk/emit! store (#'dps/persistence-failed (uuid/next) cause))
        (reset! pstate (:persistence @store))
        (dotimes [_ 2]
          (->> (dps/wait-persisted-or-error)
               (rx/subs! (fn [_] (t/is false "A failed save must still reject"))
                         (fn [error]
                           (swap! rejected conj error)
                           (errors/on-error error)))))
        (t/is (= 2 (count @rejected)))
        (t/is (= 1 (count @reports)))
        ;; A standalone timeout and a later save failure are new incidents.
        ;; The later failure carries a distinct signature: repeating the same
        ;; one inside the governor window is coalesced by design.
        (errors/on-error (ex-info "Save timed out" {:type :persistence :code :save-timeout}))
        (t/is (= 2 (count @reports)))
        (ptk/emit! store (#'dps/persistence-failed (uuid/next)
                                                   (ex-info "Save failed again" {:type :network})))
        (t/is (= 3 (count @reports)))
        (finally
          (rx/dispose! store))))))
