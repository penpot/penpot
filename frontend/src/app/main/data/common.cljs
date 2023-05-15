;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.common
  "A general purpose events."
  (:require
   [app.main.repo :as rp]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn share-link-created
  [link]
  (ptk/reify ::share-link-created
    ptk/UpdateEvent
    (update [_ state]
      (update state :share-links (fnil conj []) link))))

(defn create-share-link
  [params]
  (ptk/reify ::create-share-link
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :create-share-link params)
           (rx/map share-link-created)))))

(defn delete-share-link
  [{:keys [id] :as link}]
  (ptk/reify ::delete-share-link
    ptk/UpdateEvent
    (update [_ state]
      (update state :share-links
              (fn [links]
                (filterv #(not= id (:id %)) links))))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :delete-share-link {:id id})
           (rx/ignore)))))
