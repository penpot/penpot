;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.browser
  "Owns Chromium through process/run from spawn, then attaches Playwright by CDP.
  Shutdown stops the owned process group before disconnecting the client."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   ["playwright" :as playwright]
   [app.common.transit :as transit]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.node.process :as process]))

(def launch-settings
  "Static settings for the full Chromium executable; profile and port are owned."
  {:headless true
   :transport :cdp
   :args ["--headless"
          "--no-sandbox"
          "--disable-dev-shm-usage"
          "--enable-gpu"
          "--enable-unsafe-swiftshader"
          "--no-first-run"
          "--no-default-browser-check"
          "--disable-background-networking"
          "--disable-background-timer-throttling"
          "--disable-renderer-backgrounding"
          "--hide-scrollbars"
          "--mute-audio"]})

(defn within
  "Calls work while its absolute deadline and optional AbortSignal allow it.
  Clears the timer/listener on every outcome and handles late rejections.
  The caller owns shutdown of the browser running the work."
  [work {:keys [deadline signal phase]}]
  (js/Promise.
   (fn [resolve reject]
     (let [settled (atom false)
           timer   (atom nil)]
       (letfn [(cleanup []
                 (js/clearTimeout @timer)
                 (when signal
                   (.removeEventListener ^js signal "abort" aborted)))
               (succeeded [value]
                 (when (compare-and-set! settled false true)
                   (cleanup)
                   (resolve value)))
               (failed [cause]
                 (when (compare-and-set! settled false true)
                   (cleanup)
                   (reject cause)))
               (aborted []
                 (failed (or (.-reason ^js signal)
                             (ex-info "browser work aborted" {:type ::aborted :phase phase}))))
               (expired []
                 (failed (ex-info "browser work timed out" {:type ::timeout :phase phase})))]
         (when signal
           (.addEventListener ^js signal "abort" aborted #js {:once true}))
         (cond
           (and signal (.-aborted ^js signal))
           (aborted)

           (zero? (process/remaining deadline))
           (expired)

           :else
           (do
             (reset! timer (js/setTimeout expired (process/remaining deadline)))
             (try
               (.then (js/Promise.resolve (work)) succeeded failed)
               (catch :default cause
                 (failed cause))))))))))

(defn evaluate
  "Calls a fixed bridge method, optionally with a Transit string.
  JSON quoting embeds the argument; descriptors and results remain Transit."
  ([^js page method]
   (.evaluate page (str "window.__benchBridge." method "()")))
  ([^js page method text]
   (.evaluate page (str "window.__benchBridge." method "(" (.stringify js/JSON text) ")"))))

(defn- endpoint-waiter
  "Keeps the last 64 KiB of startup stderr until a complete endpoint line arrives."
  []
  (let [callbacks (atom nil)
        settled   (atom false)
        output    (atom "")
        address   (atom nil)
        promise   (js/Promise. (fn [resolve reject]
                                 (reset! callbacks {:resolve resolve :reject reject})))]
    ;; Cancellation may precede the first within call. Keep the endpoint's
    ;; rejection handled even when that call never subscribes to it.
    (.catch promise (fn [_] nil))
    {:promise promise
     :address address
     :on-output (fn [text stream]
                  (when (and (= :stderr stream) (not @settled))
                    (let [buffer (str @output text)]
                      (if-let [[_ endpoint] (re-find #"DevTools listening on (ws://[^\s]+)\r?\n" buffer)]
                        (do
                          (reset! settled true)
                          (reset! address endpoint)
                          (reset! output "")
                          ((:resolve @callbacks) endpoint))
                        (reset! output (subs buffer (max 0 (- (count buffer) 65536))))))))
     :failed (fn [cause]
               (when (compare-and-set! settled false true)
                 ((:reject @callbacks)
                  (ex-info "Chromium exited before its CDP endpoint became ready"
                           {:type ::endpoint-exit :stderr @output}
                           cause))))}))

(defn- ^:async stop-process
  [{:keys [controller process]}]
  (.abort controller)
  (try
    (let [result (await process)]
      (throw (ex-info "Chromium exited before owned shutdown"
                      {:type ::process-exit :exit-code (:exit-code result)})))
    (catch :default cause
      (when-not (= :process-cancelled (:type (ex-data cause)))
        (throw cause)))))

(defn- disconnect
  "Disconnects only after the group has stopped. A late CDP attachment is also
  closed by this continuation; the caller waits at most one termination grace."
  [{:keys [connection]}]
  (within (fn []
            (if-let [pending @connection]
              (.then pending
                     (fn [^js browser]
                       (when (.isConnected browser)
                         (.close browser)))
                     (fn [_] nil))
              nil))
          {:deadline (process/deadline process/default-termination-grace-ms)
           :phase :browser-disconnect}))

(defn ^:async close-page
  "Closes an owned page and its context within one termination grace.
  Repeated or already-closed pages resolve without work."
  [{:keys [page context]}]
  (when (or page context)
    (let [deadline (process/deadline process/default-termination-grace-ms)
          page-failure
          (try
            (when (and page (not (.isClosed ^js page)))
              (await (within #(.close ^js page)
                             {:deadline deadline :phase :browser-page-close})))
            nil
            (catch :default cause cause))
          context-failure
          (try
            (when context
              (await (within #(.close ^js context)
                             {:deadline deadline :phase :browser-context-close})))
            nil
            (catch :default cause cause))]
      (when-let [cause (or page-failure context-failure)]
        (throw cause)))))

(defn- ^:async shutdown-owner
  "Closes page/context best effort, then kills the owned process group. A page
  close failure does not mask process/disconnect/profile failures because the
  group kill releases the same resources."
  [{:keys [user-data-dir] :as owner}]
  (try
    (await (close-page owner))
    (catch :default _ nil))
  (let [process-failure    (try
                             (await (stop-process owner))
                             nil
                             (catch :default cause cause))
        disconnect-failure (try
                             (await (disconnect owner))
                             nil
                             (catch :default cause cause))
        profile-failure    (try
                             (fs/rmSync user-data-dir #js {:recursive true :force true})
                             nil
                             (catch :default cause cause))]
    (when-let [cause (or process-failure disconnect-failure profile-failure)]
      (throw cause))))

(defn close
  "Returns one shutdown Promise for this owner. Aborting the internal process
  lifecycle invokes process/run's SIGTERM/5s/SIGKILL group cleanup, even during
  startup or blocked page JS. Only process cancellation is normal shutdown."
  [{:keys [shutdown signal abort-listener] :as owner}]
  (or @shutdown
      (do
        (when signal
          (.removeEventListener ^js signal "abort" @abort-listener))
        (let [closing (shutdown-owner owner)]
          (reset! shutdown closing)
          closing))))

(defn ^:async launch
  "Spawns a Chromium instance and attaches via Chrome DevTools Protocol (CDP).

  Options include an optional signal, startup-timeout-ms (30000 by default)
  and an optional absolute deadline shared with server startup. The result
  owns the process group, user-data directory and CDP connection. It holds no
  page or context; use new-page for those.

  Startup failures include information about endpoint and profile."
  [{:keys [signal startup-timeout-ms deadline]
    :or {startup-timeout-ms process/default-startup-timeout-ms}}]
  (let [deadline (or deadline (process/deadline startup-timeout-ms))
        budget   (process/remaining deadline)]
    (when (and signal (.-aborted ^js signal))
      (throw (or (.-reason ^js signal) (ex-info "browser startup aborted" {:type ::aborted}))))
    (when (zero? budget)
      (throw (ex-info "browser startup timed out" {:type ::timeout :phase :browser-startup})))
    (let [^js chromium  (.-chromium playwright)
          command       (.executablePath chromium)
          user-data-dir (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-browser-"))
          controller    (js/AbortController.)
          endpoint      (endpoint-waiter)
          running       (process/run {:command command
                                      :args (into (:args launch-settings)
                                                  ["--remote-debugging-port=0"
                                                   "--remote-debugging-address=127.0.0.1"
                                                   (str "--user-data-dir=" user-data-dir)
                                                   "about:blank"])
                                      :deadline js/Number.MAX_SAFE_INTEGER
                                      :signal (.-signal controller)
                                      :capture-output? false
                                      :on-output (:on-output endpoint)})
          owner         {:controller controller :process running :shutdown (atom nil)
                         :connection (atom nil) :user-data-dir user-data-dir
                         :signal signal :abort-listener (atom nil)}
          bounds        {:deadline deadline :signal signal :phase :browser-startup}
          abort         (fn []
                          ;; Startup/caller cleanup also awaits this same promise.
                          (.catch (close owner) (fn [_] nil)))]
      (.then running
             (fn [result]
               ((:failed endpoint) (ex-info "Chromium exited during startup"
                                            {:type ::process-exit :exit-code (:exit-code result)})))
             (:failed endpoint))
      (reset! (:abort-listener owner) abort)
      (when signal
        (.addEventListener ^js signal "abort" abort #js {:once true}))
      (try
        (let [address     (await (within (fn [] (:promise endpoint)) bounds))
              ^js browser (await (within (fn []
                                           (let [connecting (.connectOverCDP chromium address
                                                                             #js {:timeout (max 1 (process/remaining deadline))})]
                                             (reset! (:connection owner) connecting)
                                             connecting))
                                         bounds))]
          (assoc owner :browser browser :version (.version browser) :endpoint address))
        (catch :default cause
          (try
            (await (close owner))
            (catch :default cleanup-cause
              (throw (ex-info "browser shutdown failed after startup error"
                              {:type ::startup-cleanup
                               :endpoint @(:address endpoint) :user-data-dir user-data-dir
                               :startup-failure (failures/describe-cause cause)}
                              cleanup-cause))))
          (throw (ex-info (or (ex-message cause) (str cause))
                          (assoc (ex-data cause) :endpoint @(:address endpoint) :user-data-dir user-data-dir)
                          cause)))))))

(defn ^:async new-page
  "Creates a context and page in a browser process we own, navigates to host,
  waits for the bridge and checks its compiled build identity.

  Returns {:page :context}.

  Failures close the started page/context before rejecting."
  [owner host {:keys [viewport signal startup-timeout-ms deadline]
               :or {startup-timeout-ms process/default-startup-timeout-ms}}]
  (let [deadline (or deadline (process/deadline startup-timeout-ms))
        bounds   {:deadline deadline :signal signal :phase :browser-page-startup}
        ^js browser (:browser owner)
        ^js context (await (within #(.newContext browser
                                                 #js {:viewport #js {:width (:width viewport)
                                                                     :height (:height viewport)}
                                                      :deviceScaleFactor (:dpr viewport)})
                                   bounds))]
    (try
      (let [page     (await (within #(.newPage context) bounds))
            page-own {:page page :context context}]
        (try
          (await (within #(.goto ^js page (str (:base-url host) "/")
                                 #js {:timeout (max 1 (process/remaining deadline))})
                         bounds))
          (await (within #(.waitForFunction ^js page "window.__benchReady === true" nil
                                            #js {:timeout (max 1 (process/remaining deadline))})
                         bounds))
          (identity/check (get-in host [:build :id])
                          (transit/decode-str (await (within #(evaluate page "buildIdentity") bounds))))
          page-own
          (catch :default cause
            (try
              (await (close-page page-own))
              (catch :default _ nil))
            (throw cause))))
      (catch :default cause
        (try
          (await (close-page {:context context}))
          (catch :default _ nil))
        (throw cause)))))

(defn connected?
  "True while the owned CDP connection is live."
  [{:keys [browser]}]
  (boolean (and browser (.isConnected ^js browser))))

(defn prepare-scene
  "Calls prepareScene and decodes its Transit response under bounds."
  [page request bounds]
  (.then (within #(evaluate page "prepareScene" request) bounds)
         transit/decode-str))

(defn run-prepared
  "Calls runPrepared and decodes its Transit response under bounds."
  [page request bounds]
  (.then (within #(evaluate page "runPrepared" request) bounds)
         transit/decode-str))

(defn screenshot
  "Writes one PNG under bounds. The timeout belongs to Playwright's call."
  [page file {:keys [deadline] :as bounds}]
  (.then (within #(.screenshot ^js page
                               #js {:path file
                                    :timeout (max 1 (process/remaining deadline))})
                 bounds)
         (fn [_] nil)))

(def service
  "Interface to the browser used by the serial runner.

  Tests can replace this map with mocks."
  {:launch launch
   :new-page new-page
   :close-page close-page
   :close close
   :connected? connected?
   :prepare prepare-scene
   :run run-prepared
   :screenshot screenshot})
