(ns uxbox.ui
  (:require [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.state :as s]
            [uxbox.util :as util]
            [uxbox.ui.users :as u]))

(def ^:private ^:static state
  (as-> (l/select-keys [:location]) $
    (l/focus-atom $ s/state)))

(defn app-render
  [own]
  (let [{:keys [location location-params]} (rum/react state)]
    (println @state)
    (case location
      :auth/login (u/login)
      ;; :auth/register (u/register)
      ;; :auth/recover (u/recover-password)
      ;; :main/dashboard (d/dashboard)
      ;; :main/project (w/workspace conn location-params)
      ;; :main/page (w/workspace conn location-params))))
      )))

(def app
  (util/component {:render app-render
                   :mixins [rum/reactive]
                   :name "app"}))

;; (rum/defc app < rum/reactive
;;   [conn location]
;;   (let [{:keys [location location-params]} (rum/react state)]
;;     (case location
;;       :auth/login (u/login)
;;       :auth/register (u/register)
;;       :auth/recover (u/recover-password)
;;       :main/dashboard (d/dashboard)
;;       :main/project (w/workspace conn location-params)
;;       :main/page    (w/workspace conn location-params))))

(defn mount!
  [el]
  (rum/mount (app) el))
