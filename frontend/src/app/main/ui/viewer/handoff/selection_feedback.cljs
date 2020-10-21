;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.selection-feedback
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   #_[app.util.object :as obj]
   #_[app.common.data :as d]
   #_[app.common.pages :as cp]
   #_[app.common.pages-helpers :as cph]
   #_[app.common.geom.matrix :as gmt]
   #_[app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   #_[app.main.refs :as refs]
   [app.main.store :as st]
   #_[app.main.data.viewer :as dv]
   #_[app.main.ui.shapes.filters :as filters]
   #_[app.main.ui.shapes.circle :as circle]
   #_[app.main.ui.shapes.frame :as frame]
   #_[app.main.ui.shapes.group :as group]
   #_[app.main.ui.shapes.icon :as icon]
   #_[app.main.ui.shapes.image :as image]
   #_[app.main.ui.shapes.path :as path]
   #_[app.main.ui.shapes.rect :as rect]
   #_[app.main.ui.shapes.text :as text]
   #_[app.main.ui.shapes.shape :refer [shape-container]]))

(def selection-rect-color-normal "#1FDEA7")
(def selection-rect-color-component "#00E0FF")
(def selection-rect-width 1)

#_(def hover-ref
  (l/derived (l/in [:viewer-local :hover]) st/state))

(defn make-hover-shapes-iref
  []
  (let [hover->shapes
        (fn [state]
          (let [hover (get-in state [:viewer-local :hover])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (map resolve-shape hover)))]
    #(l/derived hover->shapes st/state)))

(mf/defc selection-feedback [{:keys [frame]}]
  (let [hover-shapes-ref (mf/use-memo (make-hover-shapes-iref))
        hover-shapes (->> (mf/deref hover-shapes-ref)
                          (map #(gsh/translate-to-frame % frame)))]
    (for [shape hover-shapes]
      (let [{:keys [x y width height]} (:selrect shape)]
        [:rect {:x x
                :y y
                :width width
                :height height
                :fill "transparent"
                :stroke selection-rect-color-normal
                :stroke-width selection-rect-width
                :pointer-events "none"}]))))
