;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.dashboard.fonts
  (:require
   [app.common.exceptions :as ex]
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [app.main.data.messages :as dm]
   [app.util.webapi :as wa]
   [app.util.object :as obj]
   [app.util.transit :as t]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defn fetch-fonts
  [{:keys [id] :as team}]
  (ptk/reify ::fetch-fonts
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query! :team-font-variants {:team-id id})
           (rx/map (fn [items]
                     #(assoc % :dashboard-fonts (d/index-by :id items))))))))

(defn add-font
  [font]
  (ptk/reify ::add-font
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-fonts assoc (:id font) font))))


(defn update-font
  [{:keys [id font-family] :as font}]
  (ptk/reify ::update-font
    ptk/UpdateEvent
    (update [_ state]
      (let [font (assoc font :font-id (str "custom-" (str/slug font-family)))]
        (update state :dashboard-fonts assoc id font)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [font (get-in state [:dashboard-fonts id])]
        (->> (rp/mutation! :update-font-variant font)
             (rx/ignore))))))

(defn delete-font
  [{:keys [id] :as font}]
  (ptk/reify ::delete-font
    ptk/UpdateEvent
    (update [_ state]
      (update state :dashboard-fonts dissoc id))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params (select-keys font [:id :team-id])]
        (->> (rp/mutation! :delete-font-variant params)
             (rx/ignore))))))

;; (defn upload-font
;;   [{:keys [id] :as font}]
;;   (ptk/reify ::upload-font
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [{:keys [on-success on-error]
;;              :or {on-success identity
;;                   on-error rx/throw}} (meta params)]
;;         (->> (rp/mutation! :create-font-variant font)
;;              (rx/tap on-success)
;;              (rx/catch on-error))))))

;; (defn add-font
;;   "Add fonts to the state in a pending to upload state."
;;   [font]
;;   (ptk/reify ::add-font
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (let [id   (uuid/next)
;;             font (-> font
;;                      (assoc :created-at (dt/now))
;;                      (assoc :id id)
;;                      (assoc :status :draft))]
;;         (js/console.log (clj->js font))
;;         (assoc-in state [:dashboard-fonts id] font)))))
