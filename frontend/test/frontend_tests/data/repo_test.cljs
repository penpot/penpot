;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.repo-test
  (:require
   [app.main.repo :as repo]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; retryable-error? tests (synchronous)
;; ---------------------------------------------------------------------------

(t/deftest retryable-error-network
  (t/testing "network error (js/fetch failure) is retryable"
    (let [err (ex-info "network" {:type :network})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-bad-gateway
  (t/testing "502 bad-gateway is retryable"
    (let [err (ex-info "bad gateway" {:type :bad-gateway})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-service-unavailable
  (t/testing "503 service-unavailable is retryable"
    (let [err (ex-info "service unavailable" {:type :service-unavailable})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-offline
  (t/testing "offline (status 0) is retryable"
    (let [err (ex-info "offline" {:type :offline})]
      (t/is (true? (repo/retryable-error? err))))))

(t/deftest retryable-error-internal
  (t/testing "internal error (genuine bug) is NOT retryable"
    (let [err (ex-info "internal" {:type :internal :code :something})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-validation
  (t/testing "validation error is NOT retryable"
    (let [err (ex-info "validation" {:type :validation :code :request-body-too-large})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-authentication
  (t/testing "authentication error is NOT retryable"
    (let [err (ex-info "auth" {:type :authentication})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-authorization
  (t/testing "authorization/challenge error is NOT retryable"
    (let [err (ex-info "auth" {:type :authorization :code :challenge-required})]
      (t/is (not (repo/retryable-error? err))))))

(t/deftest retryable-error-no-ex-data
  (t/testing "plain error without ex-data is NOT retryable"
    (let [err (js/Error. "plain")]
      (t/is (not (repo/retryable-error? err))))))

;; ---------------------------------------------------------------------------
;; with-retry tests (async, using zero-delay config for speed)
;; ---------------------------------------------------------------------------

(def ^:private fast-config
  "Retry config with zero delay for fast tests."
  {:max-retries 3 :base-delay-ms 0})

(t/deftest with-retry-succeeds-immediately
  (t/testing "returns value when observable succeeds on first try"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/of :ok))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [val]
                (t/is (= :ok val))
                (t/is (= 1 @call-count))
                (done))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))))))))

(t/deftest with-retry-retries-on-retryable-error
  (t/testing "retries and eventually succeeds after transient failures"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (let [n (swap! call-count inc)]
                           (if (< n 3)
                             ;; First two calls fail with retryable error
                             (rx/throw (ex-info "bad gateway" {:type :bad-gateway}))
                             ;; Third call succeeds
                             (rx/of :recovered))))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [val]
                (t/is (= :recovered val))
                (t/is (= 3 @call-count))
                (done))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))))))))

(t/deftest with-retry-exhausts-retries
  (t/testing "propagates error after max retries exhausted"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "offline" {:type :offline})))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                ;; 1 initial + 3 retries = 4 total calls
                (t/is (= 4 @call-count))
                (t/is (= :offline (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-no-retry-on-non-retryable
  (t/testing "non-retryable errors propagate immediately without retry"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "auth" {:type :authentication})))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                (t/is (= 1 @call-count))
                (t/is (= :authentication (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-network-error-retried
  (t/testing "network error (js/fetch failure) is retried"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (let [n (swap! call-count inc)]
                           (if (= n 1)
                             (rx/throw (ex-info "net" {:type :network}))
                             (rx/of :ok))))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [val]
                (t/is (= :ok val))
                (t/is (= 2 @call-count))
                (done))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))))))))

(t/deftest with-retry-internal-not-retried
  (t/testing "internal error (genuine bug) is not retried"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "bug" {:type :internal
                                                   :code :something})))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                (t/is (= 1 @call-count))
                (t/is (= :internal (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-respects-max-retries-config
  (t/testing "respects custom max-retries setting"
    (t/async done
      (let [call-count (atom 0)
            config     {:max-retries 1 :base-delay-ms 0}
            obs-fn     (fn []
                         (swap! call-count inc)
                         (rx/throw (ex-info "offline" {:type :offline})))]
        (->> (repo/with-retry obs-fn config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                ;; 1 initial + 1 retry = 2 total
                (t/is (= 2 @call-count))
                (t/is (= :offline (:type (ex-data err))))
                (done))))))))

(t/deftest with-retry-mixed-errors
  (t/testing "retries retryable errors, then stops on non-retryable"
    (t/async done
      (let [call-count (atom 0)
            obs-fn     (fn []
                         (let [n (swap! call-count inc)]
                           (case n
                             1 (rx/throw (ex-info "gw" {:type :bad-gateway}))
                             2 (rx/throw (ex-info "auth" {:type :authentication}))
                             (rx/of :should-not-reach))))]
        (->> (repo/with-retry obs-fn fast-config)
             (rx/subs!
              (fn [_val]
                (t/is false "should not succeed")
                (done))
              (fn [err]
                (t/is (= 2 @call-count))
                (t/is (= :authentication (:type (ex-data err))))
                (done))))))))
