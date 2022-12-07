;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.events
  (:require
   ["ua-parser-js" :as UAParser]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.main.repo :as rp]
   [app.util.globals :as g]
   [app.util.http :as http]
   [app.util.i18n :as i18n]
   [app.util.object :as obj]
   [app.util.storage :refer [storage]]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [lambdaisland.uri :as u]
   [potok.core :as ptk]))

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
  (let [uagent (UAParser.)]
    (merge
     {:app-version (:full @cf/version)
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

(derive :app.main.data.comments/create-comment ::generic-action)
(derive :app.main.data.comments/create-comment-thread ::generic-action)
(derive :app.main.data.comments/delete-comment ::generic-action)
(derive :app.main.data.comments/delete-comment-thread ::generic-action)
(derive :app.main.data.comments/open-comment-thread ::generic-action)
(derive :app.main.data.comments/update-comment ::generic-action)
(derive :app.main.data.comments/update-comment-thread ::generic-action)
(derive :app.main.data.comments/update-comment-thread-status ::generic-action)
(derive :app.main.data.dashboard/delete-team-member ::generic-action)
(derive :app.main.data.dashboard/duplicate-project ::generic-action)
(derive :app.main.data.dashboard/create-file ::generic-action)
(derive :app.main.data.dashboard/file-created ::generic-action)
(derive :app.main.data.dashboard/invite-team-members ::generic-action)
(derive :app.main.data.dashboard/leave-team ::generic-action)
(derive :app.main.data.dashboard/move-files ::generic-action)
(derive :app.main.data.dashboard/move-project ::generic-action)
(derive :app.main.data.dashboard/project-created ::generic-action)
(derive :app.main.data.dashboard/rename-file ::generic-action)
(derive :app.main.data.dashboard/set-file-shared ::generic-action)
(derive :app.main.data.dashboard/update-team-member-role ::generic-action)
(derive :app.main.data.dashboard/update-team-photo ::generic-action)
(derive :app.main.data.dashboard/clone-template ::generic-action)
(derive :app.main.data.fonts/add-font ::generic-action)
(derive :app.main.data.fonts/delete-font ::generic-action)
(derive :app.main.data.fonts/delete-font-variant ::generic-action)
(derive :app.main.data.modal/show-modal ::generic-action)
(derive :app.main.data.users/logout ::generic-action)
(derive :app.main.data.users/request-email-change ::generic-action)
(derive :app.main.data.users/update-password ::generic-action)
(derive :app.main.data.users/update-photo ::generic-action)
(derive :app.main.data.workspace.comments/open-comment-thread ::generic-action)
(derive :app.main.data.workspace.guides/update-guides ::generic-action)
(derive :app.main.data.workspace.libraries/add-color ::generic-action)
(derive :app.main.data.workspace.libraries/add-media ::generic-action)
(derive :app.main.data.workspace.libraries/add-typography ::generic-action)
(derive :app.main.data.workspace.libraries/delete-color ::generic-action)
(derive :app.main.data.workspace.libraries/delete-media ::generic-action)
(derive :app.main.data.workspace.libraries/delete-typography ::generic-action)
(derive :app.main.data.workspace.persistence/attach-library ::generic-action)
(derive :app.main.data.workspace.persistence/detach-library ::generic-action)
(derive :app.main.data.workspace.persistence/set-file-shard ::generic-action)
(derive :app.main.data.workspace.selection/toggle-focus-mode ::generic-action)
(derive :app.main.data.workspace/create-page ::generic-action)
(derive :app.main.data.workspace/set-workspace-layout ::generic-action)
(derive :app.main.data.workspace/toggle-layout-flag ::generic-action)

(defmulti process-event ptk/type)
(defmethod process-event :default [_] nil)

(defmethod process-event ::event
  [event]
  (let [data   (deref event)
        origin (::origin data)]
    (when (::name data)
      (d/without-nils
       {:type    (::type data "action")
        :name    (::name data)
        :context (::context data)
        :props   (-> data
                     (dissoc ::name)
                     (dissoc ::type)
                     (dissoc ::origin)
                     (dissoc ::context)
                     (cond-> origin (assoc :origin origin)))}))))

(defn- normalize-props
  "Removes complex data types from props."
  [data]
  (into {}
        (comp
         (remove (fn [[_ v]] (nil? v)))
         (map (fn [[k v :as kv]]
                (cond
                  (map? v)    [k :placeholder/map]
                  (vector? v) [k :placeholder/vec]
                  (set? v)    [k :placeholder/set]
                  (coll? v)   [k :placeholder/coll]
                  (fn? v)     [k :placeholder/fn]
                  :else       kv))))
        data))

(defmethod process-event ::generic-action
  [event]
  (let [type  (ptk/type event)
        mdata (meta event)
        data  (if (satisfies? IDeref event)
                (deref event)
                {})]

    {:type    "action"
     :name    (or (::name mdata) (name type))
     :props   (-> (merge data (::props mdata))
                  (normalize-props))
     :context (d/without-nils
               {:event-origin (::origin mdata)
                :event-namespace (namespace type)
                :event-symbol (name type)})}))

(defmethod process-event :app.util.router/navigated
  [event]
  (let [match (deref event)
        route (get-in match [:data :name])
        props {:route      (name route)
               :team-id    (get-in match [:path-params :team-id])
               :file-id    (get-in match [:path-params :file-id])
               :project-id (get-in match [:path-params :project-id])}]
    {:name "navigate"
     :type "action"
     :props (normalize-props props)}))

(defmethod process-event :app.main.data.users/logged-in
  [event]
  (let [data  (deref event)
        mdata (meta data)
        props {:signin-source (::source mdata)
               :email (:email data)
               :auth-backend (:auth-backend data)
               :fullname (:fullname data)
               :is-muted (:is-muted data)
               :default-team-id (str (:default-team-id data))
               :default-project-id (str (:default-project-id data))}]
    {:name "signin"
     :type "identify"
     :profile-id (:id data)
     :props (normalize-props props)}))

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
    (let [uri    (u/join @cf/public-uri "api/rpc/command/push-audit-events")
          params {:uri uri
                  :method :post
                  :credentials "include"
                  :body (http/transit-data {:events events})}]
      (->> (http/send! params)
           (rx/mapcat rp/handle-response)
           (rx/catch (fn [_]
                       (l/error :hint "unexpected error on persisting audit events")
                       (rx/of nil)))))

    (rx/of nil)))

(defn initialize
  []
  (when (contains? @cf/flags :audit-log)
    (ptk/reify ::initialize
      ptk/EffectEvent
      (effect [_ _ stream]
        (let [session (atom nil)
              stoper  (rx/filter (ptk/type? ::initialize) stream)
              buffer  (atom #queue [])
              profile (->> (rx/from-atom storage {:emit-current-value? true})
                           (rx/map :profile)
                           (rx/map :id)
                           (rx/dedupe))]

          (l/debug :hint "event instrumentation initialized")

          (->> (rx/merge
                (->> (rx/from-atom buffer)
                     (rx/filter #(pos? (count %)))
                     (rx/debounce 2000))
                (->> stream
                     (rx/filter (ptk/type? :app.main.data.users/logout))
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
               (rx/take-until stoper)
               (rx/subs (fn [chunk]
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
                                            (assoc :session session*))]
                           (reset! session session*)
                           (-> event
                               (assoc :timestamp (dt/now))
                               (assoc :context context)))))

               (rx/tap (fn [event]
                         (l/debug :hint "event enqueued")
                         (swap! buffer append-to-buffer event)))

               (rx/switch-map #(rx/timer (inst-ms session-timeout)))
               (rx/take-until stoper)
               (rx/subs (fn [_]
                          (l/debug :hint "session reinitialized")
                          (reset! session nil))
                        (fn [cause]
                          (l/error :hint "error on event batching stream" :cause cause))
                        (fn []
                          (l/debug :hitn "events batching stream terminated")))))))))
