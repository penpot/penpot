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
    - invalid-sso-config handler  – requires :organization-id to promote to :sso-error
    - save failure notification   – sticky toast carrying the error report
    - delegated save failures     – causes handled by the handler for their type"
  (:require
   [app.common.uuid :as uuid]
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

(t/deftest persistence-notifications-include-an-error-report-download
  (let [scheduled      (atom [])
        idle-callbacks (atom [])
        events         (atom [])
        downloads      (atom [])
        revoked        (atom [])
        report         "generated error report"
        cause          (ex-info "Save failed" {:type :validation})]
    (with-redefs [dom/prevent-default         (fn [_])
                  dom/trigger-download-uri    (fn [& params]
                                                (swap! downloads conj params))
                  errors/generate-report      (fn [_] report)
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
                                                (swap! revoked conj uri))]
      (errors/flash-persistence cause)
      (doseq [callback @scheduled] (callback))
      (let [state    (ptk/update (first @events) {})
            download (get-in state [:notification :links 0])]
        (t/is (= "labels.download:report.txt" (:label download)))
        ((:callback download) nil)
        (t/is (= [["report" "text/plain" "blob:report"]] @downloads))
        (doseq [callback @idle-callbacks] (callback))
        (t/is (= ["blob:report"] @revoked))))))

(t/deftest persistence-waiters-do-not-report-an-already-handled-failure
  (let [reports  (atom [])
        rejected (atom [])
        pstate   (atom {:status :saving})
        store    (ptk/store {:state {} :on-error errors/on-error})
        cause    (ex-info "Save failed" {:type :network})]
    (with-redefs [refs/persistence pstate
                  errors/submit-report (fn [& params]
                                         (swap! reports conj (apply hash-map params)))
                  tm/schedule (mock/stub (fn [_]))]
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
        (errors/on-error (ex-info "Save timed out" {:type :persistence :code :save-timeout}))
        (t/is (= 2 (count @reports)))
        (ptk/emit! store (#'dps/persistence-failed (uuid/next) cause))
        (t/is (= 3 (count @reports)))
        (finally
          (rx/dispose! store))))))

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

(t/deftest expired-organization-sso-during-save-renews-the-session
  (t/async done
    (t/testing "an SSO-guarded save failure goes back through the identity provider"
      (let [events (atom [])]
        (mock/with-mocks
          {rp/cmd!             (mock/stub
                                (fn [_command _params]
                                  (rx/of {:authorized false
                                          :redirect-uri "https://idp.example.com/authorize"})))
           rt/get-current-href (constantly workspace-href)
           st/emit!            (mock/stub (fn [& emitted] (swap! events into emitted)))}
          (fn [done']
            (errors/flash-persistence (ex-info "SSO required" (sso-required-error)))
            (t/is (= [::rt/nav-raw] (mapv ptk/type @events)))
            (done'))
          done)))))

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

(t/deftest other-validation-failures-during-save-keep-the-notification
  (t/testing "a validation failure without a recovery of its own is still notified"
    (let [events    (atom [])
          scheduled (atom [])]
      (with-redefs [errors/generate-report (fn [_] "generated error report")
                    errors/submit-report   (fn [& _])
                    st/emit!               (mock/stub (fn [& emitted]
                                                        (swap! events into emitted)))
                    tm/schedule            (mock/stub (fn [callback]
                                                        (swap! scheduled conj callback)))]
        (errors/flash-persistence (ex-info "Invalid data" {:type :validation
                                                           :code :invalid-data}))
        (doseq [callback @scheduled] (callback))
        (t/is (= 1 (count @events)))
        (let [state (ptk/update (first @events) {})]
          (t/is (nil? (get-in state [:notification :timeout])))
          (t/is (some? (get-in state [:notification :links 0]))))))))
