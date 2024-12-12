;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.router
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.util.browser-history :as bhistory]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as e]
   [potok.v2.core :as ptk]
   [reitit.core :as r]))

;; --- Router API

(defn map->Match
  [data]
  (r/map->Match data))

(defn resolve
  ([router id] (resolve router id {}))
  ([router id params]
   (when-let [match (r/match-by-name router id)]
     (r/match->path match params))))

(defn create
  [routes]
  (r/router routes))

(defn initialize-router
  [routes]
  (ptk/reify ::initialize-router
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :router (create routes)))))

(defn match
  "Given routing tree and current path, return match with possibly
  coerced parameters. Return nil if no match found."
  [router path]
  (let [uri (u/uri path)]
    (when-let [match (r/match-by-path router (:path uri))]
      (let [query-params (u/query-string->map (:query uri))
            params       {:path (:path-params match)
                          :query query-params}]
        (-> match
            (assoc :params params)
            (assoc :query-params query-params))))))

;; --- Navigate (Event)

(defn navigated
  [match]
  (ptk/reify ::navigated
    IDeref
    (-deref [_] match)

    ev/Event
    (-data [_]
      (let [route  (dm/get-in match [:data :name])
            params (get match :path-params)]
        (assoc params
               ::ev/name "navigate"
               :route (name route))))

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :route match)
          (dissoc :exception)))))

(defn navigate
  [id params & {:keys [::replace ::new-window] :as options}]
  (ptk/reify ::navigate
    IDeref
    (-deref [_]
      {:id id
       :params params
       :options options})

    ptk/EffectEvent
    (effect [_ state _]
      (let [router  (:router state)
            history (:history state)
            path    (resolve router id params)]

        (if ^boolean new-window
          (let [name   (or (::window-name options) "_blank")
                uri    (assoc cf/public-uri :fragment path)]
            (dom/open-new-window uri name nil))
          (ts/asap
           #(if ^boolean replace
              (bhistory/replace-token! history path)
              (bhistory/set-token! history path))))))))

(defn assign-exception
  [error]
  (ptk/reify ::assign-exception
    ptk/UpdateEvent
    (update [_ state]
      (if (nil? error)
        (dissoc state :exception)
        (assoc state :exception error)))))

(defn nav
  ([id] (navigate id nil))
  ([id params] (navigate id params))
  ([id params & {:as options}]
   (navigate id params options)))

(defn get-params
  [state]
  (dm/get-in state [:route :params :query]))

(defn nav-back
  []
  (ptk/reify ::nav-back
    ptk/EffectEvent
    (effect [_ _ _]
      (ts/asap dom/browser-back))))

(defn nav-back-local
  "Navigate back only if the previous page is in penpot app."
  []
  (let [location (.-location js/document)
        referrer (u/uri (.-referrer js/document))]
    (when (or (nil? (:host referrer))
              (= (.-hostname location) (:host referrer)))
      (nav-back))))

(defn nav-root
  "Navigate to the root page."
  []
  (ptk/reify ::nav-root
    ptk/EffectEvent
    (effect [_ _ _]
      (set! (.-href globals/location) "/"))))

(defn reload
  [force?]
  (ptk/reify ::reload
    ptk/EffectEvent
    (effect [_ _ _]
      (ts/asap (partial dom/reload-current-window force?)))))

(defn nav-raw
  [& {:keys [href uri]}]
  (ptk/reify ::nav-raw
    ptk/EffectEvent
    (effect [_ _ _]
      (cond
        (string? uri)
        (.replace globals/location uri)

        (string? href)
        (set! (.-href globals/location) href)))))

(defn get-current-href
  []
  (.-href globals/location))

(defn get-current-path
  []
  (let [hash (.-hash globals/location)]
    (if (str/starts-with? hash "#")
      (subs hash 1)
      hash)))


;; --- History API

(defn initialize-history
  [on-change]
  (ptk/reify ::initialize-history
    ptk/UpdateEvent
    (update [_ state]
      (let [history (bhistory/create)]
        (bhistory/enable! history)
        (assoc state :history history)))

    ptk/EffectEvent
    (effect [_ state stream]
      (let [stopper (rx/filter (ptk/type? ::initialize-history) stream)
            history (:history state)
            router  (:router state)]
        (ts/schedule #(on-change router (.getToken ^js history)))
        (->> (rx/create (fn [subs]
                          (let [key (e/listen history "navigate" (fn [o] (rx/push! subs (.-token ^js o))))]
                            (fn []
                              (bhistory/disable! history)
                              (e/unlistenByKey key)))))
             (rx/take-until stopper)
             (rx/subs! #(on-change router %)))))))
