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
(def ^:private ^:const max-buffer-size 1024)

;; Defines the maximum number of events that can go in a single batch.
(def ^:private ^:const max-chunk-size 100)

;; Defines the time window (in ms) within events belong to the same session.
(def ^:private ^:const session-timeout (* 1000 60 30))

;; Min time for a long task to be reported to telemetry
(def ^:private ^:const min-longtask-time 1000)

;; Min time between long task reports
(def ^:private ^:const debounce-longtask-time 1000)

;; Min time for a long task to be reported to telemetry
(def ^:private ^:const min-browser-event-time 1000)

;; Min time between long task reports
(def ^:private ^:const debounce-browser-event-time 1000)

;; Min time for a long task to be reported to telemetry
(def ^:private ^:const min-performace-event-time 1000)

;; Min time between long task reports
(def ^:private ^:const debounce-performance-event-time 1000)

;; Default micro-benchmark iterations
(def ^:private ^:const micro-benchmark-iterations 1e6)

;; --- CONTEXT

(defn- collect-context
  []
  (let [uagent (new ua/UAParser)]
    (merge
     {:version (:full cf/version)
      :locale i18n/*current-locale*}
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

(add-watch i18n/locale "events" #(swap! context assoc :locale %4))

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

(defn- add-external-context-info
  [context]
  (let [external-context-info  (json/->clj (cf/external-context-info))]
    (merge context external-context-info)))

(defn- make-proto-event
  [event]
  (let [data    (d/deep-merge (-data event) (meta event))
        type    (ptk/type event)
        ev-name (name type)
        context (-> (::context data)
                    (assoc :event-origin (::origin data))
                    (assoc :event-namespace (namespace type))
                    (assoc :event-symbol ev-name)
                    (d/without-nils))
        props   (-> data d/without-qualified simplify-props)]

    {:type (::type data "action")
     :name (::name data ev-name)
     :context context
     :props props}))

(defn- make-data-event
  [event]
  (let [data (deref event)
        name (::name data)]

    (when (string? name)
      (let [type    (::type data "action")
            context (-> (::context data)
                        (assoc :event-origin (::origin data))
                        (d/without-nils))
            props   (-> data d/without-qualified simplify-props)]
        {:type    type
         :name    name
         :context context
         :props   props}))))

(defn- make-event
  "Create a standard event"
  ([result]
   (let [props      (aget result 0)
         profile-id (aget result 1)]
     (make-event profile-id props)))
  ([profile-id event]
   (when-let [event (cond
                      (satisfies? Event event)
                      (make-proto-event event)

                      (ptk/data-event? event)
                      (make-data-event event))]
     (assoc event :profile-id profile-id))))

(defn- make-performance-event
  "Create a performance trigger event"
  ([result]
   (let [props      (aget result 0)
         profile-id (aget result 1)]
     (make-performance-event profile-id props)))
  ([profile-id props]
   (let [perf-info (get @st/state :performance-info)
         name      (get props ::name)]
     {:type    "trigger"
      :name    (str "performance-" name)
      :context {:file-stats (:counters perf-info)}
      :props   (-> props
                   (dissoc ::name)
                   (assoc :file-id (:file-id perf-info)))
      :profile-id profile-id})))

(defn- process-performance-event
  "Process performance sensitive events"
  [result]
  (let [event      (aget result 0)
        profile-id (aget result 1)]
    (if (satisfies? PerformanceEvent event)
      (rx/create
       (fn [subs]
         (let [start (perf/now)]
           (js/requestAnimationFrame
            #(.postTask js/scheduler
                        (fn []
                          (let [time (- (perf/now) start)]
                            (when (> time min-performace-event-time)
                              (rx/push! subs
                                        (make-performance-event profile-id
                                                                {::name "blocking-event"
                                                                 :event-name (d/name (ptk/type event))
                                                                 :duration time})))
                            (rx/end! subs)))
                        #js {:priority "user-blocking"}))
           nil)))
      (rx/empty))))

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


(defn- user-input-observer
  "Create user interaction/input event observer. Returns rx stream."
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
                     (rx/push! subs {::name "user-input"
                                     :duration (.-duration entry)
                                     :event-name (.-name entry)})))
                 (.getEntries list))))]
         (.observe observer #js {:entryTypes #js ["event"]})
         (fn []
           (.disconnect observer)))))
    (rx/empty)))

(defn- longtask-observer
  "Create a Long-Task performance observer. Returns rx stream."
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
                               {::name "long-task"
                                :duration (.-duration entry)})))
                 (.getEntries list))))]
         (.observe observer #js {:entryTypes #js ["longtask"]})
         (fn []
           (.disconnect observer)))))
    (rx/empty)))

(defn- snapshot-performance-info
  [{:keys [file-id]}]

  (letfn [(count-shapes [file]
            (->> file :data :pages-index
                 (reduce-kv
                  (fn [sum _ page]
                    (+ sum (count (:objects page))))
                  0)))

          (add-libraries-counters [state files]
            (reduce (fn [state library-id]
                      (let [data (dm/get-in files [library-id :data])]
                        (-> state
                            (update :total-components + (count (:components data)))
                            (update :total-colors + (count (:colors data)))
                            (update :total-typographies + (count (:typographies data))))))
                    state
                    (refs/select-libraries files file-id)))]

    (ptk/reify ::snapshot-performance-info
      ptk/UpdateEvent
      (update [_ state]
        (update state :performance-info
                (fn [info]
                  (let [files (get state :files)
                        file  (get files file-id)]
                    (-> info
                        (assoc :file-id file-id)
                        (update :counters assoc :total-shapes (count-shapes file))
                        (update :counters add-libraries-counters files)))))))))

(defn- store-performace-info
  []
  (ptk/reify ::store-performace-info
    ptk/UpdateEvent
    (update [_ state]
      (let [start (perf/now)
            _     (loop [i micro-benchmark-iterations]
                    (when-not (zero? i)
                      (* (math/sin i) (math/sqrt i))
                      (recur (dec i))))
            end   (perf/now)]

        (update state :performance-info assoc :bench (- end start))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? :app.main.data.workspace/all-libraries-resolved))
           (rx/take 1)
           (rx/map deref)
           (rx/map snapshot-performance-info)))))

(defn initialize
  []
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
                       (into [] (take max-chunk-size) @buffer)))
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
                   (rx/map make-event))

              (->> (user-input-observer)
                   (rx/with-latest-from profile)
                   (rx/map make-performance-event)
                   (rx/debounce debounce-browser-event-time))

              (->> (longtask-observer)
                   (rx/with-latest-from profile)
                   (rx/map make-performance-event)
                   (rx/debounce debounce-longtask-time))

              (if (and (exists? js/globalThis)
                       (exists? (.-requestAnimationFrame js/globalThis))
                       (exists? (.-scheduler js/globalThis))
                       (exists? (.-postTask (.-scheduler js/globalThis))))
                (->> stream
                     (rx/with-latest-from profile)
                     (rx/merge-map process-performance-event)
                     (rx/debounce debounce-performance-event-time))
                (rx/empty)))

             (rx/filter :profile-id)
             (rx/map (fn [event]
                       (let [session* (or @session (ct/now))
                             context  (-> @context
                                          (merge (:context event))
                                          (assoc :session session*)
                                          (assoc :external-session-id (cf/external-session-id))
                                          (add-external-context-info)
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
                         (l/debug :hitn "events batching stream terminated"))))))))

(defn event
  [props]
  (ptk/data-event ::event props))
