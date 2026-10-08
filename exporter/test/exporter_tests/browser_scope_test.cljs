;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.browser-scope-test
  "The browser batch: every export through its own checkout, all at
  once, against a stub pool with stub pages."
  (:require
   ["generic-pool" :as gp]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [cljs.test :as t :include-macros true]
   [exporter.browser.scope :as scope]))

(defn- stub-locator
  []
  #js {:waitFor    (fn [_opts] (js/Promise.resolve nil))
       :screenshot (fn [_opts] (js/Promise.resolve nil))})

(defn- stub-page
  [calls]
  #js {:goto            (fn [url _opts]
                          (swap! calls conj [:goto (str url)])
                          (js/Promise.resolve nil))
       :waitForTimeout  (fn [ms] (swap! calls conj [:sleep ms]) (js/Promise.resolve nil))
       :waitForFunction (fn [_src _arg _opts] (js/Promise.resolve nil))
       :locator         (fn [sel] (swap! calls conj [:select sel]) (stub-locator))
       :$$              (fn [sel]
                          (swap! calls conj [:select-all sel])
                          (js/Promise.resolve #js []))
       :evaluate        (fn [_f & _args] (js/Promise.resolve nil))})

(defn- stub-context
  [page]
  #js {:newPage (fn [] (js/Promise.resolve page))
       :close   (fn [] (js/Promise.resolve nil))})

(defn- stub-browser
  [page]
  #js {:newContext  (fn [_opts] (js/Promise.resolve (stub-context page)))
       :isConnected (fn [] true)
       :close       (fn [] (js/Promise.resolve nil))})

(defn- stub-pool
  [calls]
  (let [page (stub-page calls)]
    (gp/createPool #js {:create   (fn [] (js/Promise.resolve (stub-browser page)))
                        :destroy  (fn [browser] (.close ^js browser))
                        :validate (fn [_] true)}
                   #js {:max 2 :min 0})))

(defn- test-cfg
  [calls]
  {:exporter.browser/pool (stub-pool calls)
   :base-uri              (cf/get-internal-uri)
   :public-uri            (cf/get :public-uri)
   :svgo?                 false})

(defn- test-params
  [id]
  {:file-id (uuid/next)
   :page-id (uuid/next)
   :token   "token"
   :scale   1
   :type    :png
   :objects [{:id id :name id :suffix ".png" :filename (str id ".png")}]})

(t/deftest ^:async run-fires-every-export-through-its-own-checkout
  (try
    (let [calls (atom [])
          seen  (atom [])]
      (await (scope/run (assoc (test-cfg calls)
                               :exporter.renderer/on-object (fn [object] (swap! seen conj object) nil)
                               :exporter.renderer/check-cancelled (fn [] nil))
                        [(test-params "a") (test-params "b")]))
      (t/is (= ["a" "b"] (mapv :id @seen)))
      (t/is (= 2 (count (filter #(and (vector? %) (= :goto (first %))) @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async run-rejects-on-the-first-failure
  (try
    (let [calls (atom [])]
      (try
        (await (scope/run (assoc (test-cfg calls)
                                 :exporter.renderer/on-object (fn [_] (throw (ex-info "on-object broke" {})))
                                 :exporter.renderer/check-cancelled (fn [] nil))
                          [(test-params "a")]))
        (t/is false "run should have rejected a failing on-object")
        (catch :default cause
          (t/is (= "on-object broke" (ex-message cause))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))
