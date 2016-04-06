;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.resize
  (:require [beicon.core :as rx]
            [uxbox.rstore :as rs]
            [uxbox.shapes :as ush]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.util.geom.point :as gpt]))

(declare initialize)
(declare handle-resize)

;; --- Public Api

(defn watch-resize-actions
  []
  (as-> uuc/actions-s $
    (rx/dedupe $)
    (rx/filter #(= (:type %) "ui.shape.resize") $)
    (rx/on-value $ initialize)))

;; --- Implementation

(defn- initialize
  [event]
  (let [payload (:payload event)
        stoper (->> uuc/actions-s
                    (rx/map :type)
                    (rx/filter #(empty? %))
                    (rx/take 1))]
    (as-> uuwb/mouse-delta-s $
      (rx/take-until stoper $)
      (rx/with-latest-from vector uuwb/mouse-ctrl-s $)
      (rx/subscribe $ #(handle-resize payload %)))))

(defn- handle-resize
  [{:keys [vid shape]} [delta ctrl?]]
  (let [params {:vid vid :delta (assoc delta :lock ctrl?)}]
    (rs/emit! (uds/update-vertex-position shape params))))


;; (define-once :resize-subscriptions
;;   (letfn [(init [event]
;;             (let [payload (:payload event)
;;                   stoper (->> uuc/actions-s
;;                               (rx/map :type)
;;                               (rx/filter #(empty? %))
;;                               (rx/take 1))]
;;               (as-> uuwb/mouse-delta-s $
;;                 (rx/take-until stoper $)
;;                 (rx/with-latest-from vector uuwb/mouse-ctrl-s $)
;;                 (rx/subscribe $ #(on-value payload %)))))

;;           (on-value [{:keys [vid shape]} [delta ctrl?]]
;;             (let [params {:vid vid :delta (assoc delta :lock ctrl?)}]
;;               (rs/emit! (uds/update-vertex-position shape params))))]

;;     (as-> uuc/actions-s $
;;       (rx/dedupe $)
;;       (rx/filter #(= (:type %) "ui.shape.resize") $)
;;       (rx/on-value $ init))))
