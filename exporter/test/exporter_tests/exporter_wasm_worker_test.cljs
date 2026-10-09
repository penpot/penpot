;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-wasm-worker-test
  "The render worker entry over the real render-wasm module: one render
  message runs the pipeline and posts every object plus the settle, a
  failed pipeline posts the error. The backend travels stubbed."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:os" :as os]
   ["node:path" :as path]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]
   [exporter.wasm.render :as render]
   [exporter.wasm.worker :as worker]))

(def ^:private shape-id (random-uuid))

(defn- scene-shape
  []
  {:id        shape-id
   :type      :rect
   :name      "svg-red"
   :x         10 :y 10 :width 100 :height 100
   :rotation  0
   :selrect   {:x 10 :y 10 :x1 10 :y1 10 :x2 110 :y2 110
               :width 100 :height 100}
   :strokes   []
   :svg-attrs {:fill "#ff0000"}})

(defn- test-env
  [tmpdir]
  {:internal-uri     "http://internal/"
   :public-uri       "http://public/"
   :management-key   "mgmt-key"
   :tmpdir           tmpdir
   :image-cache-size 100})

(defn- test-params
  [tmpdir]
  {:file-id  (uuid/next)
   :page-id  (uuid/next)
   :token    "tok"
   :type     :png
   :scale    1
   :objects  [{:id shape-id :name "r" :suffix "" :filename "r.png"}]
   ::render/env (test-env tmpdir)})

(defn- install-port
  [posts]
  (let [original worker/parent-port]
    (set! worker/parent-port
          #js {:postMessage (fn [msg] (swap! posts conj (js->clj msg)))})
    (fn [] (set! worker/parent-port original))))

(defn- install-fetch
  [respond]
  (let [original render/fetch]
    (set! render/fetch
          (fn [uri opts]
            (js/Promise.resolve (respond (str uri) opts))))
    (fn [] (set! render/fetch original))))

(defn- scene-fetch
  [shape]
  (install-fetch
   (fn [uri _opts]
     (cond
       (str/ends-with? uri "get-page")
       #js {:status 200
            :text   (constantly
                     (js/Promise.resolve
                      (transit/encode-str {:objects {(str (:id shape)) shape}})))}

       :else
       #js {:status 404
            :text   (constantly (js/Promise.resolve ""))
            :arrayBuffer (constantly (js/Promise.resolve nil))}))))

(t/deftest ^:async handle-render-posts-objects-and-done
  (let [tmpdir (path/join (os/tmpdir) (str "penpot-wasm-worker-test." (uuid/next)))]
    (await (fsp/mkdir tmpdir #js {:recursive true}))
    (let [posts         (atom [])
          restore-port  (install-port posts)
          restore-fetch (scene-fetch (scene-shape))]
      (try
        (await (worker/handle-render #js {:type   "render"
                                          :params (transit/encode-str (test-params tmpdir))
                                          :cancel nil}))
        (let [by-type (group-by #(get % "type") @posts)]
          (t/testing "one object posted with its file"
            (t/is (= 1 (count (get by-type "object"))))
            (let [object (transit/decode-str (get (first (get by-type "object")) "payload"))]
              (t/is (= shape-id (:id object)))
              (t/is (str/starts-with? (:path object) tmpdir))))
          (t/testing "the settle closed the lease"
            (t/is (= 1 (count (get by-type "done"))))
            (t/is (nil? (get by-type "error")))))
        (finally
          (restore-fetch)
          (restore-port)
          (await (fsp/rm tmpdir #js {:recursive true :force true})))))))

(t/deftest ^:async handle-render-posts-the-error-never-rejects
  (let [posts         (atom [])
        restore-port  (install-port posts)
        restore-fetch (install-fetch (fn [_uri _opts]
                                       (js/Promise.reject (js/Error. "backend down"))))]
    (try
      (await (worker/handle-render #js {:type   "render"
                                        :params (transit/encode-str
                                                 (test-params (os/tmpdir)))
                                        :cancel nil}))
      (let [by-type (group-by #(get % "type") @posts)]
        (t/is (nil? (get by-type "done")))
        (t/is (= 1 (count (get by-type "error"))))
        (t/is (str/includes? (get (first (get by-type "error")) "message")
                             "http fetch failed")))
      (finally
        (restore-fetch)
        (restore-port)))))
