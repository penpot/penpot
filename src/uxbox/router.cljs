(ns uxbox.router
  (:require [bidi.router]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.rstore :as rs]))

(enable-console-print!)

(defonce +router+ (volatile! nil))

(def ^:const route-l
  (as-> (l/in [:route]) $
    (l/focus-atom $ s/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-location
  [{:keys [handler route-params] :as params}]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      ;; (println "update-location" handler route-params)
      (let [route (merge {:id handler}
                          (when route-params
                            {:params route-params}))]
        (assoc state :route route)))))

(defn navigate
  ([id] (navigate id nil))
  ([id params]
   {:pre [(keyword? id)]}
   (reify
     rs/EffectEvent
     (-apply-effect [_ state]
       ;; (println "navigate" id params)
       (let [loc (merge {:handler id}
                        (when params
                          {:route-params params}))]
         (bidi.router/set-location! @+router+ loc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Router declaration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private page-route
  [[bidi/uuid :project-uuid] "/" [bidi/uuid :page-uuid]])

(def ^:const routes
  ["/" [["auth/login" :auth/login]
        ["auth/register" :auth/register]
        ["auth/recover" :auth/recover-password]
        ["dashboard/" [["projects" :dashboard/projects]
                       ["elements" :dashboard/elements]
                       ["icons" :dashboard/icons]
                       ["colors" :dashboard/colors]]]
        ["workspace/" [[page-route :workspace/page]]]]])

(defn init
  []
  (let [opts {:on-navigate #(rs/emit! (update-location %))
              :default-location {:handler :auth/login}}
        router (bidi.router/start-router! routes opts)]
    (vreset! +router+ router)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn go
  "Redirect the user to other url."
  ([id] (go id nil))
  ([id params] (rs/emit! (navigate id params))))

(defn route-for
  "Given a location handler and optional parameter map, return the URI
  for such handler and parameters."
  ([id]
   (bidi/path-for routes id))
  ([id params]
   (apply bidi/path-for routes id (into [] (mapcat (fn [[k v]] [k v])) params))))
