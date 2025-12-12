;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.event
  (:require
   ["ua-parser-js" :as ua]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.math :as math]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.globals :as g]
   [app.util.http :as http]
   [app.util.i18n :as i18n]
   [app.util.object :as obj]
   [app.util.perf :as perf]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [lambdaisland.uri :as u]
   [potok.v2.core :as ptk]))

(l/set-level! :info)

;; Defines the maximum buffer size, after events start discarding.
(def max-buffer-size 1024)

;; Defines the maximum number of events that can go in a single batch.
(def max-chunk-size 100)

;; Defines the time window (in ms) within events belong to the same session.
(def session-timeout (* 1000 60 30))


;; Min time for a long task to be reported to telemetry
(def min-longtask-time 1000)

;; Min time between long task reports
(def debounce-longtask-time 1000)

;; Min time for a long task to be reported to telemetry
(def min-browser-event-time 1000)

;; Min time between long task reports
(def debounce-browser-event-time 1000)

;; Min time for a long task to be reported to telemetry
(def min-performace-event-time 1000)

;; Min time between long task reports
(def debounce-performance-event-time 1000)

;; Def micro-benchmark iterations
(def micro-benchmark-iterations 1e6)

;; --- CONTEXT

(defn- collect-context
  []
  (let [uagent (new ua/UAParser)]
    (merge
     {:version (:full cf/version)
      :locale @i18n/locale}
     (let [browser (.getBrowser uagent)]
       {:browser (obj/get browser "name")
        :browser-version (obj/get browser "version")})
     (let [engine (.getEngine uagent)]
       {:engine (obj/get engine "name")
        :engine-version (obj/get engine "version")})
     (let [os      (.getOS uagent)
           name    (obj/get os "name")
           version (obj/get os "version")]
       {:os (str name " " version)
        :os-version version})
     (let [device (.getDevice uagent)]
       (if-let [type (obj/get device "type")]
         {:device-type type
          :device-vendor (obj/get device "vendor")
          :device-model (obj/get device "model")}
         {:device-type "unknown"}))
     (let [screen      (obj/get g/window "screen")
           orientation (obj/get screen "orientation")]
       {:screen-width (obj/get screen "width")
        :screen-height (obj/get screen "height")
        :screen-color-depth (obj/get screen "colorDepth")
        :screen-orientation (obj/get orientation "type")})
     (let [cpu (.getCPU uagent)]
       {:device-arch (obj/get cpu "architecture")}))))

(def context
  (atom (d/without-nils (collect-context))))

(add-watch i18n/locale ::events #(swap! context assoc :locale %4))

;; --- EVENT TRANSLATION

(defprotocol Event
  (-data [_] "Get event data"))

(defprotocol PerformanceEvent)

(defn- coerce-to-string
  [v]
  (cond
    (keyword? v)
    (name v)
    (string? v)
    v
    (nil? v)
    nil
    :else
    (str v)))

(def ^:private xf:coerce-to-string
  (keep coerce-to-string))

(defn- simplify-props
  "Removes complex data types from props."
  [data]
  (reduce-kv (fn [data k v]
               (cond
                 (map? v)    (assoc data k :placeholder/map)
                 (vector? v) (assoc data k (into [] xf:coerce-to-string v))
                 (set? v)    (assoc data k (into [] xf:coerce-to-string v))
                 (coll? v)   (assoc data k :placeholder/coll)
                 (fn? v)     (assoc data k :placeholder/fn)
                 (nil? v)    (dissoc data k)
                 :else       data))
             data
             data))

(defn add-external-context-info
  [context]
  (let [external-context-info  (json/->clj (cf/external-context-info))]
    (merge context external-context-info)))

(defn- process-event-by-proto
  [event]
  (let [data    (d/deep-merge (-data event) (meta event))
        type    (ptk/type event)
        ev-name (name type)
        context (-> (::context data)
                    (assoc :event-origin (::origin data))
                    (assoc :event-namespace (namespace type))
                    (assoc :event-symbol ev-name)
                    (add-external-context-info)
                    (d/without-nils))
        props   (-> data d/without-qualified simplify-props)]

    {:type (::type data "action")
     :name (::name data ev-name)
     :context context
     :props props}))

(defn- process-data-event
  [event]
  (let [data (deref event)
        name (::name data)]

    (when (string? name)
      (let [type    (::type data "action")
            context (-> (::context data)
                        (assoc :event-origin (::origin data))
                        (add-external-context-info)
                        (d/without-nils))
            props   (-> data d/without-qualified simplify-props)]
        {:type    type
         :name    name
         :context context
         :props   props}))))

(defn performance-payload
  ([result]
   (let [props      (aget result 0)
         profile-id (aget result 1)]
     (performance-payload profile-id props)))
  ([profile-id props]
   (let [{:keys [performance-info]} @st/state]
     {:type    "action"
      :name    "performance"
      :context (merge @context performance-info)
      :props   props
      :profile-id profile-id})))

(defn- process-performance-event
  [result]
  (let [event      (aget result 0)
        profile-id (aget result 1)]

    (if (and (satisfies? PerformanceEvent event)
             (exists? js/globalThis)
             (exists? (.-requestAnimationFrame js/globalThis))
             (exists? (.-scheduler js/globalThis))
             (exists? (.-postTask (.-scheduler js/globalThis))))
      (rx/create
       (fn [subs]
         (let [start (perf/timestamp)]
           (js/requestAnimationFrame
            #(js/scheduler.postTask
              (fn []
                (let [time (- (perf/timestamp) start)]
                  (when (> time min-performace-event-time)
                    (rx/push!
                     subs
                     (performance-payload
                      profile-id
                      {::event (str (ptk/type event))
                       :time time}))))
                (rx/end! subs))
              #js {"priority" "user-blocking"})))
         nil))
      (rx/empty))))

(defn- process-event
  [event]
  (cond
    (satisfies? Event event)
    (process-event-by-proto event)

    (ptk/data-event? event)
    (process-data-event event)))

;; --- MAIN LOOP

(defn- append-to-buffer
  [buffer item]
  (if (>= (count buffer) max-buffer-size)
    buffer
    (conj buffer item)))

(defn- remove-from-buffer
  [buffer items]
  (into #queue [] (drop items) buffer))

(defn- persist-events
  [events]
  (if (seq events)
    (let [uri    (u/join cf/public-uri "api/main/methods/push-audit-events")
          params {:uri uri
                  :method :post
                  :credentials "include"
                  :body (http/transit-data {:events events})}]
      (->> (http/send! params)
           (rx/mapcat rp/handle-response)
           (rx/catch (fn [_] (rx/of nil)))))

    (rx/of nil)))


(defn performance-observer-event-stream
  []
  (if (and (exists? js/globalThis)
           (exists? (.-PerformanceObserver js/globalThis)))
    (rx/create
     (fn [subs]
       (let [observer
             (js/PerformanceObserver.
              (fn [list]
                (run!
                 (fn [entry]
                   (when (and (= "event" (.-entryType entry))
                              (> (.-duration entry) min-browser-event-time))
                     (rx/push!
                      subs
                      {::event :observer-event
                       :duration (.-duration entry)
                       :event-name (.-name entry)})))
                 (.getEntries list))))]
         (.observe observer #js {:entryTypes #js ["event"]})
         (fn []
           (.disconnect observer)))))
    (rx/empty)))

(defn performance-observer-longtask-stream
  []
  (if (and (exists? js/globalThis)
           (exists? (.-PerformanceObserver js/globalThis)))
    (rx/create
     (fn [subs]
       (let [observer
             (js/PerformanceObserver.
              (fn [list]
                (run!
                 (fn [entry]
                   (when (and (= "longtask" (.-entryType entry))
                              (> (.-duration entry) min-longtask-time))
                     (rx/push! subs
                               {::event :observer-longtask
                                :duration (.-duration entry)})))
                 (.getEntries list))))]
         (.observe observer #js {:entryTypes #js ["longtask"]})
         (fn []
           (.disconnect observer)))))
    (rx/empty)))

(defn- save-performance-info
  []
  (ptk/reify ::save-performance-info
    ptk/UpdateEvent
    (update [_ state]
      (letfn [(count-shapes [file]
                (->> file :data :pages-index
                     (reduce-kv
                      (fn [sum _ page]
                        (+ sum (count (:objects page))))
                      0)))
              (count-library-data [files {:keys [id]}]
                (let [data (dm/get-in files [id :data])]
                  {:components (count (:components data))
                   :colors (count (:colors data))
                   :typographies (count (:typographies data))}))]
        (let [file-id (get state :current-file-id)
              file (get-in state [:files file-id])
              file-size (count-shapes file)

              libraries
              (-> (refs/select-libraries (:files state) (:id file))
                  (d/update-vals (partial count-library-data (:files state))))

              lib-sizes
              (->> libraries
                   (reduce-kv
                    (fn [acc _ {:keys [components colors typographies]}]
                      (-> acc
                          (update :components + components)
                          (update :colors + colors)
                          (update :typographies + typographies)))
                    {}))]
          (update state :performance-info
                  (fn [info]
                    (-> info
                        (assoc :file-size file-size)
                        (assoc :library-sizes lib-sizes)
                        (assoc :file-start-time (perf/now))))))))))

(defn store-performace-info
  []
  (letfn [(micro-benchmark [state]
            (let [start (perf/now)]
              (loop [i micro-benchmark-iterations]
                (when-not (zero? i)
                  (* (math/sin i) (math/sqrt i))
                  (recur (dec i))))
              (let [end (perf/now)]
                (update state :performance-info assoc :bench-result (- end start)))))]

    (ptk/reify ::store-performace-info
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            micro-benchmark
            (assoc-in [:performance-info :app-start-time] (perf/now))))

      ptk/WatchEvent
      (watch [_ _ stream]
        (->> stream
             (rx/filter (ptk/type? :app.main.data.workspace/all-libraries-resolved))
             (rx/take 1)
             (rx/map save-performance-info))))))

(defn initialize
  []
  (when (contains? cf/flags :audit-log)
    (ptk/reify ::initialize
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/of (store-performace-info)))

      ptk/EffectEvent
      (effect [_ _ stream]
        (let [session (atom nil)
              stopper (rx/filter (ptk/type? ::initialize) stream)
              buffer  (atom #queue [])
              profile (->> (rx/from-atom storage/user {:emit-current-value? true})
                           (rx/map :profile)
                           (rx/map :id)
                           (rx/pipe (rxo/distinct-contiguous)))]

          (l/debug :hint "event instrumentation initialized")

          (->> (rx/merge
                (->> (rx/from-atom buffer)
                     (rx/filter #(pos? (count %)))
                     (rx/debounce 2000))
                (->> stream
                     (rx/filter (ptk/type? :app.main.data.profile/logout))
                     (rx/observe-on :async)))
               (rx/map (fn [_]
                         (into [] (take max-buffer-size) @buffer)))
               (rx/with-latest-from profile)
               (rx/mapcat (fn [[chunk profile-id]]
                            (let [events (filterv #(= profile-id (:profile-id %)) chunk)]
                              (->> (persist-events events)
                                   (rx/tap (fn [_]
                                             (l/debug :hint "events chunk persisted" :total (count chunk))))
                                   (rx/map (constantly chunk))))))
               (rx/take-until stopper)
               (rx/subs! (fn [chunk]
                           (swap! buffer remove-from-buffer (count chunk)))
                         (fn [cause]
                           (l/error :hint "unexpected error on audit persistence" :cause cause))
                         (fn []
                           (l/debug :hint "audit persistence terminated"))))

          (->> (rx/merge
                (->> stream
                     (rx/with-latest-from profile)
                     (rx/map (fn [result]
                               (let [event      (aget result 0)
                                     profile-id (aget result 1)]
                                 (some-> (process-event event)
                                         (update :profile-id #(or % profile-id)))))))

                (->> (performance-observer-event-stream)
                     (rx/with-latest-from profile)
                     (rx/map performance-payload)
                     (rx/debounce debounce-browser-event-time))

                (->> (performance-observer-longtask-stream)
                     (rx/with-latest-from profile)
                     (rx/map performance-payload)
                     (rx/debounce debounce-longtask-time))

                (->> stream
                     (rx/with-latest-from profile)
                     (rx/merge-map process-performance-event)
                     (rx/debounce debounce-performance-event-time)))

               (rx/filter :profile-id)
               (rx/map (fn [event]
                         (let [session* (or @session (ct/now))
                               context  (-> @context
                                            (merge (:context event))
                                            (assoc :session session*)
                                            (assoc :external-session-id (cf/external-session-id))
                                            (d/without-nils))]
                           (reset! session session*)
                           (-> event
                               (assoc :timestamp (ct/now))
                               (assoc :context context)))))

               (rx/tap (fn [event]
                         (l/debug :hint "event enqueued")
                         (swap! buffer append-to-buffer event)))

               (rx/switch-map #(rx/timer session-timeout))
               (rx/take-until stopper)
               (rx/subs! (fn [_]
                           (l/debug :hint "session reinitialized")
                           (reset! session nil))
                         (fn [cause]
                           (l/error :hint "error on event batching stream" :cause cause))
                         (fn []
                           (l/debug :hitn "events batching stream terminated")))))))))

(defn event
  [props]
  (ptk/data-event ::event props))
