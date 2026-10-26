;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.browser-test
  (:require
   ["fs" :as fs]
   ["http" :as http]
   ["net" :as net]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.node.browser :as browser]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.server :as server]
   [cljs.test :as t :include-macros true]))

(def ^:private temporary-root (atom nil))
(def ^:private build-id "owned-browser-fixture")
(def ^:private viewport {:width 320 :height 240 :dpr 1})

(t/use-fixtures :each
  {:before #(reset! temporary-root (fs/mkdtempSync (path/join (os/tmpdir) "penpot-browser-test-")))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

(defn- ^:async open-host
  [actual-id ready?]
  (let [identity-text (.stringify js/JSON (transit/encode-str {:build-id actual-id}))]
    (fs/writeFileSync (path/join @temporary-root "index.html")
                      (str "<!doctype html><title>browser ownership</title><script>"
                           "window.__benchBridge={buildIdentity:()=>" identity-text "};"
                           "window.__benchReady=" (if ready? "true" "false") ";</script>")))
  (await (server/open {:id build-id :dir @temporary-root} {:startup-timeout-ms 3000})))

(defn- request-status
  [base-url request-path]
  (js/Promise.
   (fn [resolve reject]
     (let [address (js/URL. base-url)
           req     (http/get #js {:hostname (.-hostname address) :port (.-port address)
                                  :path request-path :agent false}
                             (fn [^js response]
                               (.once response "error" reject)
                               (.once response "end" #(resolve (.-statusCode response)))
                               (.resume response)))]
       (.once ^js req "error" reject)
       (.setTimeout ^js req 3000 #(.destroy ^js req (js/Error. "HTTP test timed out")))))))

(defn- socket-status
  [url]
  (js/Promise.
   (fn [resolve _]
     (let [address    (js/URL. url)
           ^js socket (net/createConnection #js {:host (.-hostname address)
                                                 :port (js/Number (.-port address))})]
       (.once socket "connect" #(do (.destroy socket) (resolve :open)))
       (.once socket "error" #(do (.destroy socket) (resolve (.-code ^js %))))
       (.setTimeout socket 3000 #(do (.destroy socket) (resolve :timeout)))))))

(defn- ^:async rejected
  [promise]
  (try
    (await promise)
    (t/is false "expected browser work to reject")
    nil
    (catch :default cause cause)))

(defn- ^:async check-stopped
  [{:keys [endpoint user-data-dir]}]
  (t/is (not (fs/existsSync user-data-dir)) "owned Chromium profile was removed")
  (t/is (= "ECONNREFUSED" (await (socket-status endpoint))) "the owned CDP listener is gone"))

(defn- ^:async close-fixture
  [owner host]
  (try
    (when owner
      (await (browser/close owner)))
    (finally
      (await (server/close host)))))

(t/deftest ^:async successful-close-removes-the-profile-and-cdp-listener
  (let [host      (await (open-host build-id true))
        owner-ref (atom nil)]
    (try
      (let [owner (await (browser/open host {:viewport viewport :startup-timeout-ms 30000}))]
        (reset! owner-ref owner)
        (t/is (= 200 (await (request-status (:endpoint owner) "/json/version"))))
        (t/is (= viewport
                 (js->clj (await (.evaluate ^js (:page owner)
                                            "({width:innerWidth,height:innerHeight,dpr:devicePixelRatio})"))
                          :keywordize-keys true)))
        (await (browser/close owner))
        (await (check-stopped owner))
        (t/is (not (.isConnected ^js (:browser owner))))
        (t/is (= 200 (await (request-status (:base-url host) "/"))) "browser shutdown leaves its host live")
        (await (server/close host))
        (t/is (= "ECONNREFUSED" (await (socket-status (:base-url host))))))
      (finally
        (await (close-fixture @owner-ref host))))))

(t/deftest ^:async identity-mismatch-closes-chromium-before-startup-rejects
  (let [host (await (open-host "wrong-compiled-build" true))]
    (try
      (let [cause (await (rejected (browser/open host {:viewport viewport :startup-timeout-ms 30000})))
            facts (ex-data cause)]
        (t/is (= ::identity/mismatch (:type facts)))
        (t/is (= "wrong-compiled-build" (:actual-id facts)))
        (await (check-stopped facts)))
      (finally
        (await (server/close host))))))

(t/deftest ^:async abort-stops-a-synchronously-blocked-page
  (let [host           (await (open-host build-id true))
        controller     (js/AbortController.)
        owner-ref      (atom nil)
        started-ref    (atom nil)
        started        (js/Promise. (fn [resolve _] (reset! started-ref resolve)))]
    (try
      (let [owner    (await (browser/open host {:viewport viewport :startup-timeout-ms 30000
                                                :signal (.-signal controller)}))
            ^js page (:page owner)]
        (reset! owner-ref owner)
        (await (.exposeFunction page "__blockingStarted" (fn [] (@started-ref))))
        (let [blocking (.evaluate page "window.__blockingStarted(); while (true) {}")]
          (.catch blocking (fn [_] nil))
          (await (browser/within (fn [] started)
                                 {:deadline (process/deadline 5000) :phase :test-blocking-start}))
          (let [cause (await (rejected (browser/within #(.evaluate page "1")
                                                       {:deadline (process/deadline 1000)
                                                        :phase :test-blocked-page})))]
            (t/is (= ::browser/timeout (:type (ex-data cause)))))
          (.abort controller)
          (await (browser/close owner))
          (await (check-stopped owner))
          (t/is (not (.isConnected ^js (:browser owner))))
          (t/is (instance? js/Error (await (rejected blocking))))))
      (finally
        (.abort controller)
        (await (close-fixture @owner-ref host))))))

(t/deftest ^:async abort-during-readiness-cleans-the-starting-browser
  (let [host       (await (open-host build-id false))
        controller (js/AbortController.)
        requested  (js/Promise.
                    (fn [resolve _]
                      (.once ^js (:server host) "request" (fn [_] (resolve nil)))))
        opening    (browser/open host {:viewport viewport :startup-timeout-ms 30000
                                       :signal (.-signal controller)})]
    (.catch opening (fn [_] nil))
    (try
      (await (browser/within (fn [] requested)
                             {:deadline (process/deadline 30000) :phase :test-host-request}))
      (.abort controller (ex-info "caller cancelled browser readiness" {:type ::caller-abort}))
      (let [cause (await (rejected opening))]
        (t/is (= ::caller-abort (:type (ex-data cause))))
        (await (check-stopped (ex-data cause))))
      (finally
        (.abort controller)
        (let [owner (await (.catch opening (fn [_] nil)))]
          (await (close-fixture owner host)))))))
