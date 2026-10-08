;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-browser-test
  "The browser renderer: bitmap, pdf and svg flows through a real
  pool with fake pages. No text nodes in the svg fixture means no
  shell-outs; the exported file itself proves the reassembly."
  (:require
   ["generic-pool" :as gp]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.util.shell :as sh]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]
   [exporter.renderer.browser :as render]
   [exporter.renderer.svg-gradient :as grad]))

(defn- stub-locator
  [calls evaluate-fn]
  #js {:waitFor    (fn [_opts] (swap! calls conj :locator-wait) (js/Promise.resolve nil))
       :screenshot (fn [opts]
                     (swap! calls conj [:screenshot (unchecked-get opts "path")])
                     (js/Promise.resolve nil))
       :evaluate   (fn [f & _args] (js/Promise.resolve (evaluate-fn f)))})

(defn- stub-page
  [calls locator]
  #js {:goto            (fn [url _opts]
                          (swap! calls conj [:goto (str url)])
                          (js/Promise.resolve nil))
       :waitForTimeout  (fn [ms] (swap! calls conj [:sleep ms]) (js/Promise.resolve nil))
       :waitForFunction (fn [_src _arg _opts]
                          (swap! calls conj :wait-for-fonts)
                          (js/Promise.resolve nil))
       :locator         (fn [sel] (swap! calls conj [:select sel]) locator)
       :$$              (fn [sel]
                          (swap! calls conj [:select-all sel])
                          (js/Promise.resolve #js []))
       :evaluate        (fn [_f & _args] (swap! calls conj :page-eval) (js/Promise.resolve nil))
       :pdf             (fn [opts]
                          (swap! calls conj [:pdf (unchecked-get opts "path")])
                          (js/Promise.resolve nil))})

(defn- stub-context
  [calls page]
  #js {:newPage (fn [] (js/Promise.resolve page))
       :close   (fn [] (swap! calls conj :close-context) (js/Promise.resolve nil))})

(defn- stub-browser
  [calls page]
  #js {:newContext  (fn [_opts] (js/Promise.resolve (stub-context calls page)))
       :isConnected (fn [] true)
       :close       (fn [] (js/Promise.resolve nil))})

(defn- test-pool
  [calls page]
  (gp/createPool #js {:create   (fn [] (js/Promise.resolve (stub-browser calls page)))
                      :destroy  (fn [browser] (.close ^js browser))
                      :validate (fn [_] true)}
                 #js {:max 1 :min 0}))

(defn- test-cfg
  [pool]
  {:exporter.browser/pool pool
   :base-uri              (cf/get-internal-uri)
   :public-uri            (cf/get :public-uri)
   :svgo?                 false})

(defn- never-cancelled
  []
  (fn [] nil))

(defn- raising-check
  [calls raise-on-call]
  (let [seen (atom 0)]
    (fn []
      (swap! calls conj :check-cancelled)
      (when (= raise-on-call (swap! seen inc))
        (throw (ex-info "export job was cancelled"
                        {:type :internal
                         :code :job-cancelled
                         :hint "export job was cancelled"}))))))

(defn- test-object
  [id]
  {:id id :name id})

(defn- test-params
  [type ids]
  {:file-id  (uuid/next)
   :page-id  (uuid/next)
   :token    "token"
   :scale    1
   :type     type
   :objects  (mapv test-object ids)})

(defn- collect-on-object
  []
  (let [seen (atom [])]
    {:seen seen
     :fn   (fn [object] (swap! seen conj object) nil)}))

(t/deftest ^:async renders-bitmaps-through-one-page
  (try
    (let [calls   (atom [])
          page    (stub-page calls (stub-locator calls (fn [_] nil)))
          pool    (test-pool calls page)
          {:keys [seen fn]} (collect-on-object)]
      (await (render/render (test-cfg pool) (test-params :png ["a" "b"]) fn (never-cancelled)))
      (t/is (= 2 (count @seen)))
      (t/is (every? #(str/ends-with? (:path %) ".png") @seen))
      (t/is (= ["a" "b"] (mapv :id @seen)))
      (t/testing "one navigation for the whole partition"
        (t/is (= 1 (count (filter #(and (vector? %) (= :goto (first %))) @calls))))
        (t/is (str/includes? (second (first (filter #(and (vector? %) (= :goto (first %))) @calls)))
                             "render.html")))
      (t/is (some #{[:select "#screenshot-a"]} @calls))
      (t/is (some #{[:select "#screenshot-b"]} @calls)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async renders-pdf-navigating-per-object
  (try
    (let [calls   (atom [])
          page    (stub-page calls (stub-locator calls (fn [_] nil)))
          pool    (test-pool calls page)
          {:keys [seen fn]} (collect-on-object)]
      (await (render/render (test-cfg pool) (test-params :pdf ["a" "b"]) fn (never-cancelled)))
      (t/is (= 2 (count @seen)))
      (t/is (every? #(str/ends-with? (:path %) ".pdf") @seen))
      (t/testing "one navigation per object"
        (t/is (= 2 (count (filter #(and (vector? %) (= :goto (first %))) @calls)))))
      (t/is (= 2 (count (filter #(and (vector? %) (= :pdf (first %))) @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancelled-render-rejects-before-navigating
  (try
    (let [calls (atom [])
          page  (stub-page calls (stub-locator calls (fn [_] nil)))
          pool  (test-pool calls page)
          cfg   (test-cfg pool)]
      (try
        (await (render/render cfg (test-params :png ["a"])
                              (fn [_] (swap! calls conj :object))
                              (raising-check calls 1)))
        (t/is false "render should have rejected a cancelled render")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code)))
          (t/is (not-any? #(and (vector? %) (= :goto (first %))) @calls))
          (t/is (not-any? #{:object} @calls)))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async cancel-mid-batch-stops-the-pdf-loop
  (try
    (let [calls   (atom [])
          page    (stub-page calls (stub-locator calls (fn [_] nil)))
          pool    (test-pool calls page)
          ;; entry check is the first call, then one per object:
          ;; the cancel lands as the second object comes up
          cfg     (test-cfg pool)
          {:keys [seen fn]} (collect-on-object)]
      (try
        (await (render/render cfg (test-params :pdf ["a" "b"]) fn (raising-check calls 3)))
        (t/is false "render should have rejected a cancelled batch")
        (catch :default cause
          (t/is (= :job-cancelled (-> cause ex-data :code)))
          (t/is (= ["a"] (mapv :id @seen)))
          (t/is (= 1 (count (filter #(and (vector? %) (= :goto (first %))) @calls)))))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest ^:async renders-svg-reassembling-the-document
  (try
    (let [calls   (atom [])
          outer   "<svg><g id=\"shape-a\"></g>&nbsp;</svg>"
          locator (stub-locator calls (fn [f] (f #js {:outerHTML outer})))
          page    (stub-page calls locator)
          pool    (test-pool calls page)
          {:keys [seen fn]} (collect-on-object)
          _       (await (render/render (test-cfg pool) (test-params :svg ["a"]) fn (never-cancelled)))
          path    (:path (first @seen))
          content (await (sh/read-file path))]
      (t/is (= 1 (count @seen)))
      (t/is (str/ends-with? path ".svg"))
      (t/is (str/includes? content "<svg>"))
      (t/is (not (str/includes? content "&nbsp;"))))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

(t/deftest nbsp-entity-becomes-its-svg-compatible-form
  (t/is (= "a&#160;b" (render/sanitize-svg-content "a&nbsp;b")))
  (t/is (= "plain" (render/sanitize-svg-content "plain"))))

(t/deftest gradient-def-names-shapes
  (let [stops  [{"color" "#000000" "offset" 0 "opacity" 1}]
        linear (grad/data->gradient-def "tid" ["#ff0000" {"gradient" {"type" "linear" "stops" stops}}])
        radial (grad/data->gradient-def "tid" ["#00ff00" {"gradient" {"type" "radial" "stops" stops}}])]
    (t/is (= "linearGradient" (get linear "name")))
    (t/is (= "radialGradient" (get radial "name")))
    (t/is (= "gradient-tid-ff0000" (get-in linear ["attributes" "id"])))
    (t/is (= 1 (count (get linear "elements"))))))
