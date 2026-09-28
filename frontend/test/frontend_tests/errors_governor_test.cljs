;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.errors-governor-test
  "Unit tests for the error report governor (`app.main.errors`).

  Tests cover:
    - error fingerprinting     – stable identity incl. wrapper stacks
    - reserve-report*          – pure window/count/eviction decisions
    - submit-report            – governed emission, thunk reports
    - flash report pipeline    – reserve-before-generate, totality"
  (:require
   [app.main.data.event :as ev]
   [app.main.errors :as errors]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.timers :as tm]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

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

(t/deftest fingerprint-skips-non-frame-preamble-lines
  ;; Scenario: a bundler/polyfill wrapper prepends extra non-frame lines
  ;; before the real frames. Positional indexing would take the preamble
  ;; as the frame and fragment the grouping; matching the first frame
  ;; line keeps the identity equal to the plain equivalent. Proves:
  ;; grouping survives wrapper-prepended stacks.
  (let [plain   (doto (ex-info "boom" {:type :internal :hint "boom"})
                  (unchecked-set "stack" "Error: boom\n    at call-site-a (app.js:1)"))
        wrapped (doto (ex-info "boom" {:type :internal :hint "boom"})
                  (unchecked-set "stack" "Error: boom\nWrapped by polyfill\n    at call-site-a (app.js:1)"))]
    (t/is (= (errors/error-fingerprint "handled-exception" plain)
             (errors/error-fingerprint "handled-exception" wrapped)))))

(t/deftest environment-fingerprints-ignore-the-stack-frame
  (let [cause-a  (doto (ex-info "http error" {:type :offline :hint "http error"})
                   (unchecked-set "stack" "Error: http error\n    at call-site-a (app.js:1)"))
        cause-b  (doto (ex-info "http error" {:type :offline :hint "http error"})
                   (unchecked-set "stack" "Error: http error\n    at call-site-b (app.js:2)"))
        defect-a (doto (ex-info "boom" {:type :internal :hint "boom"})
                   (unchecked-set "stack" "Error: boom\n    at call-site-a (app.js:1)"))
        defect-b (doto (ex-info "boom" {:type :internal :hint "boom"})
                   (unchecked-set "stack" "Error: boom\n    at call-site-b (app.js:2)"))]
    (t/testing "environment failures group across internal call sites"
      (t/is (= (errors/error-fingerprint "handled-exception" cause-a)
               (errors/error-fingerprint "handled-exception" cause-b))))
    (t/testing "application defects keep the stack frame in their identity"
      (t/is (not= (errors/error-fingerprint "handled-exception" defect-a)
                  (errors/error-fingerprint "handled-exception" defect-b))))))

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

(t/deftest submit-report-evaluates-thunk-reports-lazily
  ;; Scenario: the same failure is submitted twice with the report as a
  ;; thunk. The first occurrence is granted and builds the report; the
  ;; second is suppressed and must never run the thunk. Proves: suppressed
  ;; occurrences skip the report-building cost entirely.
  (let [calls  (atom 0)
        report (fn [] (swap! calls inc) "report")
        cause  (error-cause :type :network :hint "boom")
        events (capture-reports!
                (fn []
                  (errors/submit-report :event-name "handled-exception"
                                        :report report
                                        :hint "boom"
                                        :cause cause)
                  (errors/submit-report :event-name "handled-exception"
                                        :report report
                                        :hint "boom"
                                        :cause cause)))]
    (t/is (= 1 (count events)))
    (t/is (= 1 @calls) "only the granted occurrence builds the report")
    (t/is (= "report" (:report (deref (first events)))))))

(t/deftest governor-applies-to-every-report-name
  (let [events (capture-reports!
                (fn []
                  (doseq [event-name ["handled-exception" "unhandled-exception" "exception-page"]]
                    (let [cause (error-cause :type :internal :hint event-name)]
                      (errors/submit-report :event-name event-name
                                            :report "report"
                                            :hint event-name
                                            :cause cause)
                      (errors/submit-report :event-name event-name
                                            :report "report"
                                            :hint event-name
                                            :cause cause)))))]
    (t/is (= 3 (count events)))))

(t/deftest submit-report-without-cause-is-ignored
  (let [events (capture-reports!
                (fn []
                  (errors/submit-report :event-name "exception-page" :report "report" :hint "boom")
                  (errors/submit-report :event-name "exception-page"
                                        :report "report"
                                        :hint "boom"
                                        :cause (error-cause :type :internal :hint "boom"))))]
    ;; The cause-less call is ignored and must not consume the reservation
    ;; of the cause-based report.
    (t/is (= 1 (count events)))
    (t/is (= 1 (:occurrences (deref (first events)))))))

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

(defn- report-events
  "The audit events (report emissions) out of everything collected through
  the `st/emit!` double. Toasts are ungoverned by design — one per `flash`
  call — so only the events typed as audit events count for emission
  bounds. Discriminates by `ptk/type`, which is total (never throws),
  because toasts are not derefable."
  [events]
  (filter #(= ::ev/event (ptk/type %)) events))

(t/deftest ^:async flash-suppressed-occurrence-does-not-build-a-report
  ;; Scenario: the same failure flashes 3 times inside the governor window.
  ;; Only the first occurrence reserves emission; the suppressed ones skip
  ;; generation entirely. Proves: one audit event and a single report build
  ;; for the whole burst; each `flash` is awaited to completion, so no
  ;; timer reasoning is involved.
  (let [generated (atom 0)
        cause     (error-cause :type :internal :hint "unable to perform fetch operation")
        events    (atom [])]
    (await
     (mock/with-mocks*
       {st/format-last-events (mock/stub (fn [& _] (swap! generated inc) "report"))
        st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))}
       (dotimes [_ 3]
         (await (errors/flash :cause cause :type :handled)))
       (t/is (= 1 (count (report-events @events))))
       (t/is (= 1 @generated))))))

(t/deftest ^:async flash-bounds-an-incident-like-loop
  ;; Scenario: 10 000 identical flashes, replaying the incident loop. Every
  ;; flash is awaited (all 10 000 scheduled at once, one wait for all of
  ;; them), but the governor emits only the first occurrence. Proves: the
  ;; burst produces exactly one audit event and one report build.
  (let [generated (atom 0)
        cause     (error-cause :type :internal :hint "unable to perform fetch operation")
        events    (atom [])]
    (await
     (mock/with-mocks*
       {st/format-last-events (mock/stub (fn [& _] (swap! generated inc) "report"))
        st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))}
       (await (js/Promise.all
               (into-array
                (map (fn [_] (errors/flash :cause cause :type :handled))
                     (range 10000)))))
       (t/is (= 1 (count (report-events @events))))
       (t/is (= 1 @generated))))))

(t/deftest generate-report-is-total-when-formatting-fails
  (with-redefs [st/format-last-events (mock/stub (fn [& _] (throw (ex-info "formatting failed" {}))))]
    (let [report (errors/generate-report (error-cause :type :network :hint "boom"))]
      (t/is (string? report)))))

(t/deftest ^:async flash-emits-a-fallback-report-when-generation-fails
  ;; Scenario: the full-report formatter throws. `generate-report` stays
  ;; total and the fallback string is what gets emitted. Proves: a
  ;; formatting failure still produces exactly one audit event carrying a
  ;; string report, with the `flash` awaited to completion.
  (let [events (atom [])]
    (await
     (mock/with-mocks*
       {st/format-last-events (mock/stub (fn [& _] (throw (ex-info "formatting failed" {}))))
        st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))}
       (await (errors/flash :cause (error-cause :type :internal :hint "boom") :type :handled))
       (let [reports (report-events @events)]
         (t/is (= 1 (count reports)))
         (t/is (string? (:report (deref (first reports))))))))))

(t/deftest ^:async flash-emits-nothing-synchronously
  ;; Scenario: a single handled failure. Nothing report- or toast-related
  ;; may run on the error handler's stack: the whole `flash` body executes
  ;; inside one scheduled callback. Proves: zero events right after the
  ;; call returns; report + toast once the returned promise completes.
  (errors/reset-report-governor!)
  (let [events (atom [])
        cause  (error-cause :type :internal :hint "async flash probe")]
    (await
     (mock/with-mocks*
       {st/emit!            (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href (constantly "https://penpot.example.com/#/workspace")
        tm/schedule         (mock/stub (fn [f] (mock/asap f)))}
       (let [completed (errors/flash :cause cause :type :handled)]
         (t/is (empty? @events) "nothing emitted synchronously")
         (await completed)
         (t/is (= 2 (count @events)) "report + toast once completed"))))))

(t/deftest ^:async flash-report-failure-neither-propagates-nor-kills-the-toast
  ;; Scenario: the report emission itself throws (audit sink down). The
  ;; failure is logged and swallowed at the schedule boundary while the
  ;; toast is still attempted, so the user keeps the banner. Proves:
  ;; `flash` never throws, its promise still completes, and a reporting
  ;; failure still leaves the toast emitted.
  (errors/reset-report-governor!)
  (let [events (atom [])
        calls  (atom 0)
        cause  (error-cause :type :internal :hint "emit failure probe")]
    (await
     (mock/with-mocks*
       {st/emit!            (mock/stub (fn [& emitted]
                                         (if (zero? @calls)
                                           (do (swap! calls inc)
                                               (throw (ex-info "sink down" {})))
                                           (swap! events into emitted))))
        rt/get-current-href (constantly "https://penpot.example.com/#/workspace")
        tm/schedule         (mock/stub (fn [f] (mock/asap f)))}
       (await (errors/flash :cause cause :type :handled))
       (t/is (= 1 (count @events)) "only the toast survives a reporting failure")))))

(defn- toast-links
  "The download links of every toast collected through the `st/emit!`
  double, one entry per toast."
  [events]
  (->> events
       (remove #(= ::ev/event (ptk/type %)))
       (map #(get-in (ptk/update % {}) [:notification :links]))))

(t/deftest ^:async repeated-flash-keeps-the-report-download-link
  ;; Scenario: the same save failure flashes twice inside the governor
  ;; window with a report link. The repeat is not emitted, but its toast
  ;; still offers the report. Proves: the governor bounds emission, never
  ;; the user's download.
  (let [generated (atom 0)
        cause     (error-cause :type :validation :hint "save failed")
        events    (atom [])]
    (await
     (mock/with-mocks*
       {st/format-last-events (mock/stub (fn [& _] (swap! generated inc) "report"))
        st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))}
       (dotimes [_ 2]
         (await (errors/flash :cause cause :type :handled :report-link? true)))
       (t/is (= 1 (count (report-events @events))) "the repeat is not emitted")
       (t/is (= [1 1] (map count (toast-links @events))) "both toasts carry the link")
       (t/is (= 2 @generated) "the repeat builds its report for the link only")))))

(t/deftest ^:async unreportable-flash-keeps-the-report-download-link
  ;; Scenario: a failure with an empty hint flashes with a report link. It
  ;; cannot be emitted, but its toast still offers the report. Proves: the
  ;; link does not depend on emission.
  (let [cause  (ex-info "" {:type :validation})
        events (atom [])]
    (await
     (mock/with-mocks*
       {st/format-last-events (mock/stub (fn [& _] "report"))
        st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))
        rt/get-current-href   (constantly "https://penpot.example.com/#/workspace")
        tm/schedule           (mock/stub (fn [f] (mock/asap f)))}
       (await (errors/flash :cause cause :type :handled :report-link? true))
       (t/is (empty? (report-events @events)) "nothing is emitted")
       (t/is (= [1] (map count (toast-links @events))) "the toast carries the link")))))
