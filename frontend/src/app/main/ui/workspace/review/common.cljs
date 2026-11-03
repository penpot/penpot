;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.review.common
  "Pieces shared by the review mode and the tasks: who someone is, what a
  task points at, and the chip that names a layer."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn profile-name
  "The name of the person `profile-id`, or a dash when nobody knows it."
  [profiles profile-id]
  (get-in profiles [profile-id :fullname] "—"))

(defn status-label
  [status]
  (case status
    "done"     (tr "workspace.tasks.status.done")
    "reopened" (tr "workspace.tasks.status.reopened")
    "verified" (tr "workspace.tasks.status.verified")
    "wont-do"  (tr "workspace.tasks.status.wont-do")
    (tr "workspace.tasks.status.open")))

(defn board-name
  "The name of the board `frame-id`: what the header of a thread or a
  task says it is on."
  [objects frame-id]
  (let [frame (get objects frame-id)]
    (if (or (nil? frame) (cfh/root? frame))
      (tr "workspace.tasks.canvas")
      (:name frame))))

(defn layer-name
  "The name of the layer a task points at: the lost one when it was
  deleted, its board when it points at none."
  [objects {:keys [shape-id frame-id lost-shape-name]}]
  (let [shape (get objects shape-id)]
    (or lost-shape-name
        (when (and (some? shape) (not (cfh/root? shape)))
          (:name shape))
        (board-name objects frame-id))))

(defn thread-anchor-label
  "What a comment thread points at, as its chip says it: the layer, the
  size of the area, or the board of the spot."
  [objects {:keys [anchor-type shape-id area frame-id]}]
  (case anchor-type
    :layer (or (some-> (get objects shape-id) :name)
               (tr "workspace.review.anchor.deleted-layer"))
    :area  (tr "workspace.review.anchor.area"
               (mth/round (:width area))
               (mth/round (:height area)))
    (board-name objects frame-id)))

(defn thread-anchor-icon
  [{:keys [anchor-type]}]
  (case anchor-type
    :layer i/board
    :area  i/comment-area
    i/pin))

(mf/defc layer-chip*
  "A chip naming a layer (or what a thread points at). A lost layer reads
  in the warning tone, struck through when `is-struck`."
  [{:keys [label icon is-lost is-struck class]}]
  [:span {:class [class (stl/css-case :layer-chip true
                                      :is-lost is-lost
                                      :is-struck is-struck)]}
   [:> icon* {:icon-id (if is-lost i/triangle-alert (or icon i/board)) :size "s"}]
   [:span {:class (stl/css :layer-chip-label)} label]])
