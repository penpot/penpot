;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.common
  "Pieces shared by the review mode and the tasks: who someone is, what a
  task points at, and what a change did."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]))

(defn profile-name
  "The name of the person `profile-id`, or a dash when nobody knows it."
  [profiles profile-id]
  (or (get-in profiles [profile-id :fullname])
      (tr "workspace.review.unknown-name")))

(defn status-tone
  "The tone a task in `status` is drawn in, the colour of its state."
  [status]
  (case status
    "done"     "success"
    "reopened" "warning"
    "verified" "purple"
    "wont-do"  "error"
    "info"))

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

(defn change-kind-label
  "What a change did to its layer."
  [kind]
  (case kind
    :added   (tr "workspace.review.change.added")
    :deleted (tr "workspace.review.change.deleted")
    (tr "workspace.review.change.modified")))

(defn change-kind-label-lower
  "What a change did to its layer, in the middle of a sentence."
  [kind]
  (case kind
    :added   (tr "workspace.review.change.added-lower")
    :deleted (tr "workspace.review.change.deleted-lower")
    (tr "workspace.review.change.modified-lower")))

(defn event->canvas
  "The canvas point under a pointer `event` on a layer that covers the
  viewport."
  [event vbox zoom]
  (let [rect (dom/get-bounding-rect (dom/get-current-target event))
        pt   (dom/get-client-position event)]
    {:x (+ (:x vbox) (/ (- (:x pt) (:left rect)) zoom))
     :y (+ (:y vbox) (/ (- (:y pt) (:top rect)) zoom))}))
