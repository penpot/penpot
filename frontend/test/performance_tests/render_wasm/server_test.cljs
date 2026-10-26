;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns performance-tests.render-wasm.server-test
  (:require
   ["fs" :as fs]
   ["http" :as http]
   ["net" :as net]
   ["os" :as os]
   ["path" :as path]
   [benches.render-wasm.node.server :as server]
   [cljs.test :as t :include-macros true]))

(def ^:private temporary-root (atom nil))
(def ^:private wasm-bytes [0 97 115 109 1 0 0 0])

(defn- write-file
  [file content]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync file content))

(t/use-fixtures :each
  {:before (fn []
             (let [root (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-http-"))
                   dir  (path/join root "selected")]
               (reset! temporary-root root)
               (write-file (path/join dir "index.html") "<!doctype html><title>selected</title>")
               (write-file (path/join dir "browser" "browser.js") "export const selected = true;")
               (write-file (path/join dir "cases.cjs") "module.exports = {};")
               (write-file (path/join dir "renderer" "render-wasm.wasm")
                           (js/Buffer.from (to-array wasm-bytes)))
               (write-file (path/join root "selected-outside" "secret.txt") "private sibling")
               (write-file (path/join root "outside.txt") "private file")))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

(defn- build
  []
  {:id "selected-http-build" :dir (path/join @temporary-root "selected")})

(defn- request
  [host request-path method]
  (js/Promise.
   (fn [resolve reject]
     (let [address (js/URL. (:base-url host))
           req     (http/request
                    ;; Supplying a raw path avoids URL normalization hiding traversal.
                    #js {:hostname (.-hostname address)
                         :port (.-port address)
                         :path request-path
                         :method method
                         :agent false}
                    (fn [response]
                      (let [chunks (atom [])]
                        (.on ^js response "data" #(swap! chunks conj %))
                        (.once ^js response "error" reject)
                        (.once ^js response "end"
                               #(resolve {:status (.-statusCode response)
                                          :headers (js->clj (.-headers response))
                                          :body (js/Buffer.concat (to-array @chunks))})))))]
       (.once ^js req "error" reject)
       (.setTimeout ^js req 3000 #(.destroy ^js req (js/Error. "test request timed out")))
       (.end ^js req)))))

(defn- ^:async request-failure
  [host]
  (try
    (await (request host "/" "GET"))
    nil
    (catch :default error error)))

(t/deftest ^:async serves-only-the-selected-build-with-head-and-artifact-mime-types
  (let [selected (build)
        host     (await (server/open selected))]
    (try
      (t/is (= selected (:build host)))
      (t/is (re-matches #"http://127\.0\.0\.1:[0-9]+" (:base-url host)))
      (let [index   (await (request host "/?unused=query" "GET"))
            browser (await (request host "/browser/browser.js" "GET"))
            cjs     (await (request host "/cases.cjs" "GET"))
            wasm    (await (request host "/renderer/render-wasm.wasm" "GET"))
            head    (await (request host "/renderer/render-wasm.wasm" "HEAD"))]
        (t/is (= 200 (:status index)))
        (t/is (= "<!doctype html><title>selected</title>" (.toString (:body index) "utf8")))
        (t/is (= "text/html; charset=utf-8" (get-in index [:headers "content-type"])))
        (t/is (= "export const selected = true;" (.toString (:body browser) "utf8")))
        (t/is (= "application/javascript" (get-in browser [:headers "content-type"])))
        (t/is (= "application/javascript" (get-in cjs [:headers "content-type"])))
        (t/is (= "application/wasm" (get-in wasm [:headers "content-type"])))
        (t/is (zero? (js/Buffer.compare (:body wasm) (js/Buffer.from (to-array wasm-bytes)))))
        (t/is (= 200 (:status head)))
        (t/is (= "8" (get-in head [:headers "content-length"])))
        (t/is (zero? (.-length (:body head)))))
      (t/is (= 404 (:status (await (request host "/selected-outside/secret.txt" "GET")))))
      (t/is (= 405 (:status (await (request host "/" "POST")))))
      (finally
        (await (server/close host))))))

(t/deftest ^:async rejects-raw-traversal-and-outside-file-and-directory-symlinks
  (let [selected (build)
        dir      (:dir selected)]
    (fs/symlinkSync (path/join @temporary-root "outside.txt") (path/join dir "escape.txt"))
    (fs/symlinkSync (path/join @temporary-root "selected-outside") (path/join dir "linked") "dir")
    (let [host (await (server/open selected))]
      (try
        (doseq [request-path ["/../selected-outside/secret.txt"
                              "/%2e%2e/selected-outside/secret.txt"
                              "/%2e%2e%2fselected-outside%2fsecret.txt"
                              "/%5c..%5cselected-outside%5csecret.txt"
                              "/escape.txt" "/linked/secret.txt"]]
          (let [response (await (request host request-path "GET"))]
            (t/is (= 403 (:status response)) request-path)
            (t/is (= "forbidden" (.toString (:body response) "utf8")) request-path)))
        (t/is (= 400 (:status (await (request host "/%zz" "GET")))))
        (t/is (= 403 (:status (await (request host "/%00" "GET")))))
        (t/is (= 404 (:status (await (request host "/missing-file" "GET")))))
        (t/is (= 404 (:status (await (request host "/browser" "GET")))))
        (finally
          (await (server/close host)))))))

(defn- connect
  [host]
  (js/Promise.
   (fn [resolve reject]
     (let [address (js/URL. (:base-url host))
           socket  (net/createConnection #js {:host "127.0.0.1" :port (js/Number (.-port address))})]
       (.once ^js socket "error" reject)
       (.once ^js socket "connect" #(resolve socket))))))

(defn- bounded
  [promise]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout #(reject (js/Error. "test shutdown timed out")) 3000)]
       (.then promise
              (fn [value] (js/clearTimeout timer) (resolve value))
              (fn [error] (js/clearTimeout timer) (reject error)))))))

(t/deftest ^:async close-destroys-owned-connections-and-keeps-other-servers-live
  (let [host (await (server/open (build)))]
    (try
      (let [other (await (server/open (build)))]
        (try
          (let [socket (await (connect host))
                closed (js/Promise. (fn [resolve _] (.once ^js socket "close" resolve)))]
            (try
              (await (bounded (js/Promise.all (to-array [(server/close host) (server/close host)]))))
              (await (bounded closed))
              (t/is (some? (await (request-failure host))))
              (let [response (await (request other "/" "GET"))]
                (t/is (= 200 (:status response)))
                (t/is (= "<!doctype html><title>selected</title>" (.toString (:body response) "utf8"))))
              (await (server/close host))
              (finally
                (.destroy ^js socket))))
          (finally
            (await (server/close other)))))
      (finally
        (await (server/close host))))))

(defn- ^:async startup-failure
  [opening]
  (try
    (let [host (await opening)]
      (await (server/close host))
      nil)
    (catch :default error error)))

(t/deftest ^:async startup-rejects-already-aborted-and-in-flight-signals
  (let [already (js/AbortController.)]
    (.abort already)
    (let [error (await (startup-failure (server/open (build) {:signal (.-signal already)})))]
      (t/is (= ::server/aborted (:type (ex-data error))))))
  (let [controller (js/AbortController.)
        opening    (server/open (build) {:signal (.-signal controller)})]
    (.abort controller)
    (let [error (await (startup-failure opening))]
      (t/is (= ::server/aborted (:type (ex-data error))))))
  (let [host (await (server/open (build) {:startup-timeout-ms 3000}))]
    (try
      (t/is (= 200 (:status (await (request host "/" "GET")))))
      (finally
        (await (server/close host))))))
