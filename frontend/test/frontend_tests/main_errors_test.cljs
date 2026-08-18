;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.main-errors-test
  "Unit tests for app.main.errors.

  Tests cover:
    - stale-asset-error?          – pure predicate
    - exception->error-data       – pure transformer
    - on-error re-entrancy guard  – prevents recursive invocations
    - flash schedules async emit  – ntf/show is not emitted synchronously
    - organization SSO recovery   – expired SSO sessions go back to the provider"
  (:require
   [app.main.errors :as errors]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
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
  (t/testing "the browser is sent to the identity provider instead of an error page"
    (let [events (atom [])]
      (with-redefs [rp/cmd!
                    (mock/stub
                     (fn [_command _params]
                       (rx/of {:authorized false
                               :redirect-uri "https://idp.example.com/authorize"})))

                    rt/get-current-href
                    (constantly workspace-href)

                    st/emit!
                    (mock/stub (fn [& emitted] (swap! events into emitted)))]

        (errors/on-error (sso-required-error))

        (t/is (= [::rt/nav-raw] (mapv ptk/type @events)))))))

(t/deftest expired-organization-sso-comes-back-to-the-current-location
  (t/testing "the SSO check asks the provider to return the user where they were"
    (let [rpc-calls (atom [])]
      (with-redefs [rp/cmd!
                    (mock/stub
                     (fn [command params]
                       (swap! rpc-calls conj {:command command :params params})
                       (rx/of {:authorized false
                               :redirect-uri "https://idp.example.com/authorize"})))

                    rt/get-current-href
                    (constantly workspace-href)

                    st/emit! mock/noop]

        (errors/on-error (sso-required-error))

        (t/is (= [{:command :check-nitrate-sso
                   :params {:team-id "b8f8bb52-8b70-8144-8004-4a5085f0bdc9"
                            :organization-id organization-id
                            :url workspace-href}}]
                 @rpc-calls))))))

(t/deftest already-satisfied-organization-sso-retries-the-location
  (t/testing "a session renewed meanwhile (e.g. in another tab) reloads instead of erroring"
    (let [events (atom [])]
      (with-redefs [rp/cmd!
                    (mock/stub
                     (fn [_command _params]
                       (rx/of {:authorized true :reason :sso-satisfied})))

                    rt/get-current-href
                    (constantly workspace-href)

                    st/emit!
                    (mock/stub (fn [& emitted] (swap! events into emitted)))]

        (errors/on-error (sso-required-error))

        (t/is (= [::rt/reload] (mapv ptk/type @events)))))))

(t/deftest organization-sso-without-usable-provider-shows-the-sso-error-dialog
  (t/testing "SSO is required but there is nowhere to go: offer a retry, not a permission error"
    (let [assigned* (atom nil)]
      (with-redefs [rp/cmd!
                    (mock/stub
                     (fn [_command _params]
                       (rx/of {:authorized false :redirect-uri nil})))

                    rt/get-current-href
                    (constantly workspace-href)

                    rt/assign-exception
                    (fn [error]
                      (reset! assigned* error)
                      (ptk/data-event ::assigned error))]

        (errors/on-error (sso-required-error))

        (t/is (= :sso-error (:type @assigned*)))
        (t/is (= organization-id (:organization-id @assigned*)))
        (t/is (true? (:is-workspace @assigned*)))))))

(t/deftest organization-sso-without-team-access-reports-a-permission-failure
  (t/testing "a user who cannot reach the team keeps getting the authentication error"
    (let [assigned* (atom nil)]
      (with-redefs [rp/cmd!
                    (mock/stub
                     (fn [_command _params]
                       (rx/of {:authorized true :reason :no-team-access})))

                    rt/get-current-href
                    (constantly workspace-href)

                    rt/assign-exception
                    (fn [error]
                      (reset! assigned* error)
                      (ptk/data-event ::assigned error))]

        (errors/on-error (sso-required-error))

        (t/is (= :authentication (:type @assigned*)))
        (t/is (= :nitrate-sso-required (:code @assigned*)))))))

(t/deftest organization-sso-does-not-retry-on-an-unexplained-authorization
  (t/testing "reloading on an answer we don't understand would spin on the same rejection"
    (let [events (atom [])]
      (with-redefs [rp/cmd!
                    (mock/stub (fn [_command _params] (rx/of {:authorized true})))

                    rt/get-current-href
                    (constantly workspace-href)

                    rt/assign-exception
                    (fn [error] (ptk/data-event ::assigned error))

                    ;; async-emit! is variadic-only, so the replacement must be
                    ;; variadic too for the compiled static dispatch to find it
                    st/async-emit!
                    (fn [& emitted] (swap! events into emitted))]

        (errors/on-error (sso-required-error))

        (t/is (= [::assigned] (mapv ptk/type @events)))))))

(t/deftest organization-sso-error-without-context-is-reported-as-it-arrives
  (t/testing "with no organization and no team there is nothing to check"
    (let [rpc-calls (atom 0)
          assigned* (atom nil)]
      (with-redefs [rp/cmd!
                    (mock/stub (fn [_command _params]
                                 (swap! rpc-calls inc)
                                 (rx/empty)))

                    rt/get-current-href
                    (constantly workspace-href)

                    rt/assign-exception
                    (fn [error]
                      (reset! assigned* error)
                      (ptk/data-event ::assigned error))]

        (errors/on-error {:type :authentication
                          :code :nitrate-sso-required})

        (t/is (zero? @rpc-calls))
        (t/is (= :nitrate-sso-required (:code @assigned*)))))))

(t/deftest a-resultless-organization-sso-check-does-not-wedge-later-rejections
  (t/testing "the one-in-flight guard is released even when no answer arrives"
    (let [rpc-calls (atom 0)]
      (with-redefs [rp/cmd!
                    (mock/stub (fn [_command _params]
                                 (swap! rpc-calls inc)
                                 (rx/empty)))

                    rt/get-current-href
                    (constantly workspace-href)

                    st/emit! mock/noop]

        (errors/on-error (sso-required-error))
        (errors/on-error (sso-required-error))

        (t/is (= 2 @rpc-calls))))))

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
