(ns uxbox.router
  (:require [bidi.router]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [uxbox.rstore :as rs]))


(enable-console-print!)

(declare +router+)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-location
  [{:keys [handler route-params]}]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (merge state
             {:location handler}
             (when route-params
               {:location-params route-params})))))

(defn navigate
  ([name] (navigate name nil))
  ([name params]
   {:pre [(keyword? name)]}
   (reify
     rs/EffectEvent
     (-apply-effect [_ state]
       (let [loc (merge {:handler name}
                        (when params
                          {:route-params params}))]
         (bidi.router/set-location! +router+ loc))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Router declaration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private project-route
  [bidi/uuid :project-uuid])

(def ^:private page-route
  [[bidi/uuid :project-uuid] "/" [bidi/uuid :page-uuid]])

(def ^:static
  routes ["/" [["auth/login" :auth/login]
               ["auth/register" :auth/register]
               ["auth/recover" :auth/recover-password]
               ["dashboard" :main/dashboard]
               ["workspace/" [[project-route :main/project]
                              [page-route :main/page]]]]])

(defn- on-navigate
  [data]
  (println "on-navigate:" data)
  (rs/emit! (update-location data)))

(defonce +router+
  (bidi.router/start-router! routes {:on-navigate on-navigate
                                     :default-location {:handler :login}}))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn go
  "Redirect the user to other url."
  ([name] (go name nil))
  ([name params] (rs/emit! (navigate name params))))
