;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-wasm-render-test
  "The headless render pipeline, without a render-wasm module: endpoint
  building, error explaining, font-variant routing and the backend
  fetches over a stubbed transport. The Skia calls themselves are
  covered by the svg integration test."
  (:require
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [exporter.wasm.render :as render]))

(def ^:private env
  {:internal-uri    "http://internal/"
   :public-uri      "http://public/"
   :management-key  "mgmt-key"
   :tmpdir          "/tmp/penpot-wasm-render-test"
   :image-cache-size 100})

(t/deftest endpoint-builders-join-against-the-env-uris
  (t/is (= "http://internal/api/rpc/command/get-page"
           (render/internal-uri env "api/rpc/command/get-page")))
  (t/is (= "http://public/assets/by-id/123"
           (render/public-uri env "assets/by-id/123"))))

(t/deftest error-detail-flattens-the-fetch-cause-chain
  (let [root  (doto (js/Error. "connect ECONNREFUSED")
                (unchecked-set "code" "ECONNREFUSED"))
        cause (doto (js/Error. "fetch failed")
                (unchecked-set "cause" root))]
    (t/is (= "fetch failed <- ECONNREFUSED: connect ECONNREFUSED"
             (render/error-detail cause)))
    (t/is (= "boom" (render/error-detail (js/Error. "boom"))))))

(t/deftest find-variant-degrades-uuid-weight-then-uuid
  (let [variants [{:font-id "f1" :font-weight 400 :font-style :normal}
                  {:font-id "f1" :font-weight 700 :font-style :italic}]]
    (t/is (= {:font-id "f1" :font-weight 400 :font-style :normal}
             (render/find-variant variants "f1" 400 0)))
    (t/is (= {:font-id "f1" :font-weight 700 :font-style :italic}
             (render/find-variant variants "f1" 700 1)))
    (t/is (= {:font-id "f1" :font-weight 400 :font-style :normal}
             (render/find-variant variants "f1" 400 1))
          "style degrades before weight")
    (t/is (nil? (render/find-variant variants "f9" 400 0)))))

(t/deftest family-uuid-roundtrips-through-u32
  ;; `:id` is a JS array (never a clj vector: `aget` on a vector
  ;; reads `undefined`, which the u32 setter coerces to zero).
  (let [id    (uuid/next)
        quart (uuid/get-u32 id)]
    (t/is (= id (render/family-uuid #js [(aget quart 0) (aget quart 1)
                                         (aget quart 2) (aget quart 3)])))))

(t/deftest scene-fallback-fonts-covers-emoji
  (let [text-shape (fn [text]
                     {:type    :text
                      :content {:children [{:children [{:children [{:text text}]}]}]}})
        latin      {(uuid/next) (text-shape "hello")}
        emoji      {(uuid/next) (text-shape "hi 😀")}]
    (t/is (= [] (render/scene-fallback-fonts latin)))
    (t/is (true? (boolean (some :is-emoji (render/scene-fallback-fonts emoji)))))))

(defn- stub-fetch
  [calls respond]
  (set! render/fetch
        (fn [uri opts]
          (swap! calls conj {:uri (str uri) :opts opts})
          (js/Promise.resolve (respond (str uri))))))

(t/deftest ^:async fetch-objects-decodes-the-get-page-answer
  (let [calls    (atom [])
        original render/fetch
        file-id  (uuid/next)
        page-id  (uuid/next)
        shape    {:id "s1" :type :rect}]
    (stub-fetch calls
                (fn [_uri]
                  #js {:status 200
                       :text   (constantly
                                (js/Promise.resolve
                                 (transit/encode-str {:objects {"s1" shape}})))}))
    (try
      (let [objects (await (render/fetch-objects
                            env {:file-id  file-id
                                 :page-id  page-id
                                 :token    "tok"
                                 :objects  [{:id "s1"}]}))]
        (t/testing "the scene travels back"
          (t/is (= {"s1" shape} objects)))
        (t/testing "the call rode the internal rpc with auth"
          (let [{:keys [uri opts]} (first @calls)]
            (t/is (= "http://internal/api/rpc/command/get-page" uri))
            (t/is (= "Bearer tok" (unchecked-get (.-headers opts) "Authorization")))
            (t/is (= "exporter mgmt-key" (unchecked-get (.-headers opts) "X-Shared-Key"))))))
      (finally
        (set! render/fetch original)))))

(t/deftest ^:async fetch-font-variants-degrades-to-nil
  (let [original render/fetch]
    (try
      (stub-fetch (atom [])
                  (fn [_uri] #js {:status 500
                                  :text   (constantly (js/Promise.resolve ""))}))
      (t/is (nil? (await (render/fetch-font-variants env {:file-id (uuid/next)
                                                          :token   "tok"})))
            "a non-200 is fallback fonts, not a failed export")
      (set! render/fetch (fn [_uri _opts] (js/Promise.reject (js/Error. "down"))))
      (t/is (nil? (await (render/fetch-font-variants env {:file-id (uuid/next)
                                                          :token   "tok"})))
            "a transport failure is fallback fonts too")
      (finally
        (set! render/fetch original)))))

(t/deftest ^:async fetch-file-media-bytes-passes-bytes-through
  (let [original render/fetch]
    (try
      (stub-fetch (atom [])
                  (fn [_uri] #js {:status      200
                                  :arrayBuffer (constantly
                                                (js/Promise.resolve
                                                 (.from js/Buffer "img")))}))
      (let [buf (await (render/fetch-file-media-bytes env (uuid/next) "tok"))]
        (t/is (= 3 (.-byteLength buf))))
      (stub-fetch (atom [])
                  (fn [_uri] #js {:status      404
                                  :arrayBuffer (constantly
                                                (js/Promise.resolve nil))}))
      (t/is (nil? (await (render/fetch-file-media-bytes env (uuid/next) "tok"))))
      (finally
        (set! render/fetch original)))))
