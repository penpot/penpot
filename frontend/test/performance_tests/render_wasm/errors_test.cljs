;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.errors-test
  (:require
   [app.common.transit :as transit]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(t/deftest attempt-returns-the-value-of-its-body
  (t/is (= 3 (attempt 1 2 3)))
  (t/is (nil? (attempt nil)))
  (t/is (= {:error :not-a-failure} (attempt {:error :not-a-failure}))))

(t/deftest attempt-returns-the-error-its-body-throws
  (let [cause  (ex-info "boom" {:a 1})
        result (attempt (throw cause))]
    (t/is (failure? result))
    (t/is (identical? cause result))))

(t/deftest attempt-turns-a-thrown-value-into-an-error-that-keeps-its-text
  (let [text   (attempt (throw "device disconnected"))
        object (attempt (throw #js {:code 7}))]
    (t/is (failure? text))
    (t/is (= "device disconnected" (ex-message text)))
    (t/is (failure? object))
    (t/is (str/includes? (ex-message object) "code"))))

(t/deftest attempt-throws-fatal-failures-again
  (let [cause (errors/fatal-error "write failed" {:path "a"} (js/Error. "disk"))]
    (t/is (thrown-with-msg? js/Error #"write failed"
                            (attempt (throw cause))))
    (t/is (identical? cause (attempt :catch-fatal (throw cause))))))

(t/deftest attempt-treats-a-fatal-cause-deep-in-the-chain-as-fatal
  (let [fatal (errors/fatal-error "write failed" {} nil)
        outer (ex-info "outer" {} (ex-info "middle" {} fatal))]
    (t/is (true? (errors/fatal? outer)))
    (t/is (thrown-with-msg? js/Error #"outer" (attempt (throw outer))))))

(t/deftest fatal-marker-is-not-assumed-from-other-data
  (t/is (false? (errors/fatal? (ex-info "x" {:type :anything}))))
  (t/is (false? (errors/fatal? nil)))
  (t/is (false? (errors/fatal? "text"))))

(t/deftest ^:async attempt-awaits-promises-in-an-async-function
  (let [value   (attempt (await (js/Promise.resolve 7)))
        failure (attempt (await (js/Promise.reject (ex-info "rejected" {}))))
        bound   (let [x (attempt :catch-fatal (await (js/Promise.resolve 1)) 2)] x)]
    (t/is (= 7 value))
    (t/is (= "rejected" (ex-message failure)))
    (t/is (= 2 bound))))

(t/deftest ^:async attempt-keeps-fatal-rejections-fatal
  (let [cause (errors/fatal-error "write failed" {} nil)
        outer (try
                (attempt (await (js/Promise.reject cause)))
                nil
                (catch :default thrown thrown))]
    (t/is (identical? cause outer))))

(t/deftest with-data-passes-non-failures-through
  (t/is (= 1 (errors/with-data 1 {:a 1})))
  (t/is (nil? (errors/with-data nil {:a 1})))
  (t/is (= {:a 1} (errors/with-data {:a 1} {:a 2}))))

(t/deftest with-data-adds-keys-under-the-failure-data
  (let [cause  (ex-info "boom" {:log-path "inner"})
        result (errors/with-data cause {:log-path "outer" :build-id "b"})]
    (t/is (= "boom" (ex-message result)))
    (t/is (= {:log-path "inner" :build-id "b"} (ex-data result)))
    (t/is (identical? cause (ex-cause result)))))

(t/deftest message-of-falls-back-to-the-string-form
  (t/is (= "boom" (errors/message-of (ex-info "boom" {}))))
  (t/is (= "text" (errors/message-of "text"))))

(t/deftest context-keeps-deeper-measurements-and-native-cause-details
  (let [native (ex-info "render failed" {:fn "_render" :code 17})
        cause  (ex-info "interact failed" {:phase "interact" :partial {:slices [1 2]}} native)
        error  (errors/with-context cause "setup failed"
                 {:phase "setup" :partial {:slices [1] :setup {:upload-ms 3}}})
        result (errors/evidence :setup error)]
    (t/is (= {:phase "setup" :partial {:slices [1 2] :setup {:upload-ms 3}}}
             (ex-data error)))
    (t/is (= "setup failed" (:message result)))
    (t/is (some #(= {:message "render failed" :fn "_render" :code 17} %)
                (:cause result)))))

(t/deftest context-keeps-native-errors-as-diagnostic-causes
  (let [error (errors/with-context (js/Error. "device lost") "restore failed" {:phase "restore"})]
    (t/is (some #(= "device lost" (:message %)) (errors/describe-cause error)))))

(t/deftest hostile-cause-renders-plain-data
  (let [hostile (ex-info "boom"
                         {:fn (fn [] 1) :code #js {:evil 1} :type :wasm-error
                          :hint (str (apply str (repeat 499 "x")) "😀")}
                         (js/Error. "inner boom"))
        result  (errors/evidence :aborted hostile)
        decoded (-> result transit/encode-str transit/decode-str)
        outer   (first (:cause decoded))]
    (t/is (= "aborted" (:phase decoded)))
    (t/is (= "boom" (:message decoded)))
    (t/is (string? (:fn outer)))
    (t/is (string? (:code outer)))
    (t/is (= (apply str (repeat 499 "x")) (:hint outer)))
    (t/is (some #(= "inner boom" (:message %)) (:cause decoded)))))

(t/deftest unprintable-values-produce-portable-diagnostics
  (let [evil (js-obj)]
    (js/Object.defineProperty evil "evil"
                              #js {:enumerable true
                                   :get (fn [] (throw (js/Error. "evil")))})
    (let [data    (errors/evidence :upload (ex-info "evil boom" {:fn evil}))
          cause   (errors/evidence :upload evil)
          decoded (-> [data cause] transit/encode-str transit/decode-str)]
      (t/is (= "unrenderable value" (get-in decoded [0 :cause 0 :fn])))
      (t/is (= "unrenderable cause" (get-in decoded [1 :cause 0 :message]))))))

(t/deftest non-finite-failure-details-cross-transit-as-numbers
  (let [failure (ex-info "bad measurement" {:code js/NaN :hint js/Infinity :type js/-Infinity})
        decoded (-> (errors/evidence :render failure) transit/encode-str transit/decode-str)
        detail  (first (:cause decoded))]
    (t/is (js/Number.isNaN (:code detail)))
    (t/is (= js/Infinity (:hint detail)))
    (t/is (= js/-Infinity (:type detail)))))
