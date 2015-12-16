(ns uxbox.router
  (:require [bidi.router]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [uxbox.state]
            [uxbox.rstore :as rs]))

(enable-console-print!)

(declare +router+)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-location
  [{:keys [handler route-params] :as params}]
  (reify
    IPrintWithWriter
    (-pr-writer [mv writer x]
      (-write writer "#<event:router/update-location ")
      (-pr-writer params writer x)
      (-write writer ">"))

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
     IPrintWithWriter
     (-pr-writer [mv writer _]
       (-write writer "#<event:router/navigate>"))

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
  [[bidi/uuid :project-uuid]])

(def ^:private page-route
  [[bidi/uuid :project-uuid] "/" [bidi/uuid :page-uuid]])

(def ^:static
  routes ["/" [["auth/login" :auth/login]
               ;; ["auth/register" :auth/register]
               ;; ["auth/recover" :auth/recover-password]
               ["dashboard/" [["projects" :dashboard/projects]
                              ["elements" :dashboard/elements]
                              ["icons" :dashboard/icons]
                              ["colors" :dashboard/colors]]]
               ["workspace/" [[project-route :main/project]
                              [page-route :main/page]]]]])

(defonce +router+
  (let [opts {:on-navigate #(rs/emit! (update-location %))
              :default-location {:handler :dashboard/projects}}]
    (bidi.router/start-router! routes opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn go
  "Redirect the user to other url."
  ([name] (go name nil))
  ([name params] (rs/emit! (navigate name params))))

(defn route-for
  "Given a location handler and optional parameter map, return the URI
  for such handler and parameters."
  ([location]
   (bidi/path-for routes location))
  ([location params]
   (apply bidi/path-for routes location (into []
                                              (mapcat (fn [[k v]] [k v]))
                                              params))))
