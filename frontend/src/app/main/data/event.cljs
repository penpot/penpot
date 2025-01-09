;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.event
  (:require
   ["ua-parser-js" :as ua]
   [app.common.data :as d]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.main.repo :as rp]
   [app.util.globals :as g]
   [app.util.http :as http]
   [app.util.i18n :as i18n]
   [app.util.object :as obj]
   [app.util.storage :as storage]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [lambdaisland.uri :as u]
   [potok.v2.core :as ptk]))

(l/set-level! :info)

;; Defines the maximum buffer size, after events start discarding.
(def max-buffer-size 1024)

;; Defines the maximum number of events that can go in a single batch.
(def max-chunk-size 100)

;; Defines the time window within events belong to the same session.
(def session-timeout
  (dt/duration {:minutes 30}))

;; --- CONTEXT

(defn- collect-context
  []
  (let [uagent (new ua/UAParser)]
    (merge
     {:app-version (:full cf/version)
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

(defn- simplify-props
  "Removes complex data types from props."
  [data]
  (reduce-kv (fn [data k v]
               (cond
                 (map? v)    (assoc data k :placeholder/map)
                 (vector? v) (assoc data k :placeholder/vec)
                 (set? v)    (assoc data k :placeholder/set)
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
    (let [uri    (u/join cf/public-uri "api/rpc/command/push-audit-events")
          params {:uri uri
                  :method :post
                  :credentials "include"
                  :body (http/transit-data {:events events})}]
      (->> (http/send! params)
           (rx/mapcat rp/handle-response)
           (rx/catch (fn [_] (rx/of nil)))))

    (rx/of nil)))

(defn initialize
  []
  (when (contains? cf/flags :audit-log)
    (ptk/reify ::initialize
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

          (->> stream
               (rx/with-latest-from profile)
               (rx/map (fn [result]
                         (let [event      (aget result 0)
                               profile-id (aget result 1)]
                           (some-> (process-event event)
                                   (update :profile-id #(or % profile-id))))))
               (rx/filter :profile-id)
               (rx/map (fn [event]
                         (let [session* (or @session (dt/now))
                               context  (-> @context
                                            (merge (:context event))
                                            (assoc :session session*)
                                            (assoc :external-session-id (cf/external-session-id))
                                            (d/without-nils))]
                           (reset! session session*)
                           (-> event
                               (assoc :timestamp (dt/now))
                               (assoc :context context)))))

               (rx/tap (fn [event]
                         (l/debug :hint "event enqueued")
                         (swap! buffer append-to-buffer event)))

               (rx/switch-map #(rx/timer (inst-ms session-timeout)))
               (rx/take-until stopper)
               (rx/subs! (fn [_]
                           (l/debug :hint "session reinitialized")
                           (reset! session nil))
                         (fn [cause]
                           (l/error :hint "error on event batching stream" :cause cause))
                         (fn []
                           (l/debug :hitn "events batching stream terminated")))))))))
