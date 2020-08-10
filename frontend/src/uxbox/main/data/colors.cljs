;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.colors
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [clojure.set :as set]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.color :as color]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.common.uuid :as uuid]))


(declare create-color-result)

(defn create-color
  [file-id color]
  (s/assert (s/nilable uuid?) file-id)
  (ptk/reify ::create-color
    ptk/WatchEvent
    (watch [_ state s]

      (->> (rp/mutation! :create-color {:file-id file-id
                                        :content color
                                        :name color})
           (rx/map (partial create-color-result file-id))))))

(defn create-color-result
  [file-id color]
  (ptk/reify ::create-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-colors-library (:id color)] color)
          (assoc-in [:workspace-local :color-for-rename] (:id color))))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(declare rename-color-result)

(defn rename-color
  [file-id color-id name]
  (ptk/reify ::rename-color
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :rename-color {:id color-id
                                        :name name})
           (rx/map (partial rename-color-result file-id))))))

(defn rename-color-result
  [file-id color]
  (ptk/reify ::rename-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-colors-library (:id color)] color)))))

(declare update-color-result)

(defn update-color
  [file-id color-id content]
  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :update-color {:id color-id
                                        :content content})
           (rx/map (partial update-color-result file-id))))))

(defn update-color-result
  [file-id color]
  (ptk/reify ::update-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-colors-library (:id color)] color)))))

(declare delete-color-result)

(defn delete-color
  [file-id color-id]
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :delete-color {:id color-id})
           (rx/map #(delete-color-result file-id color-id))))))

(defn delete-color-result
  [file-id color-id]
  (ptk/reify ::delete-color-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/dissoc-in [:workspace-colors-library color-id])))))

