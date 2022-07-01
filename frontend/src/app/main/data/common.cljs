;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.common
  "A general purpose events."
  (:require
   [app.main.repo :as rp]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn share-link-created-updated
  [link]
  (ptk/reify ::share-link-created-updated
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :share-link link))))

(defn create-share-link
  [params]
  (ptk/reify ::create-share-link
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation! :create-share-link params)
           (rx/map share-link-created-updated)))))

(defn update-share-link
  [params]
  (ptk/reify ::update-share-link
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation! :update-share-link params)
           (rx/map share-link-created-updated)))))

(defn delete-share-link
  [{:keys [id] :as link}]
  (ptk/reify ::delete-share-link
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :share-link))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation! :delete-share-link {:id id})
           (rx/ignore)))))

