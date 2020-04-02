;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.shapes.frame
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.dom :as dom]
   [uxbox.util.interop :as itr]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

(declare frame-wrapper)

(def frame-default-props {:fill-color "#ffffff"})

(declare frame-shape)
(declare translate-to-frame)

(defn wrap-memo-frame
  ([component]
   (mf/memo'
    component
    (fn [np op]
      (let [n-shape (aget np "shape")
            o-shape (aget op "shape")
            n-objs  (aget np "objects")
            o-objs  (aget op "objects")

            ids (:shapes n-shape)]
        (and (identical? n-shape o-shape)
             (loop [id (first ids)
                    ids (rest ids)]
               (if (nil? id)
                 true
                 (if (identical? (get n-objs id)
                                 (get o-objs id))
                   (recur (first ids) (rest ids))
                   false)))))))))


(defn frame-wrapper [shape-wrapper]
  (mf/fnc frame-wrapper
    {::mf/wrap [wrap-memo-frame]}
    [{:keys [shape objects] :as props}]
    (when (and shape (not (:hidden shape)))
      (let [selected-iref (-> (mf/deps (:id shape))
                              (mf/use-memo #(refs/make-selected (:id shape))))
            selected? (mf/deref selected-iref)
            on-mouse-down #(common/on-mouse-down % shape)
            on-context-menu #(common/on-context-menu % shape)
            shape (merge frame-default-props shape)
            {:keys [x y width height]} shape

            childs (mapv #(get objects %) (:shapes shape))

            ds-modifier (:displacement-modifier shape)
            label-pos (cond-> (gpt/point x (- y 10))
                        (gmt/matrix? ds-modifier) (gpt/transform ds-modifier))

            on-double-click
            (fn [event]
              (dom/prevent-default event)
              (st/emit! dw/deselect-all
                        (dw/select-shape (:id shape))))]
        [:g {:class (when selected? "selected")
             :on-context-menu on-context-menu
             :on-double-click on-double-click
             :on-mouse-down on-mouse-down}
         [:text {:x (:x label-pos)
                 :y (:y label-pos)
                 :width width
                 :height 20
                 :class-name "workspace-frame-label"
                 :on-click on-double-click} ; user may also select with single click in the label
          (:name shape)]
         [:& (frame-shape shape-wrapper) {:shape shape
                                          :childs childs}]]))))

(defn frame-shape [shape-wrapper]
 (mf/fnc frame-shape
   [{:keys [shape childs] :as props}]
   (let [rotation    (:rotation shape)
         ds-modifier (:displacement-modifier shape)
         rz-modifier (:resize-modifier shape)

         shape (cond-> shape
                 (gmt/matrix? rz-modifier) (geom/transform rz-modifier)
                 (gmt/matrix? ds-modifier) (geom/transform ds-modifier))

         {:keys [id x y width height]} shape

         props (-> (attrs/extract-style-attrs shape)
                   (itr/obj-assign!
                    #js {:x 0
                         :y 0
                         :id (str "shape-" id)
                         :width width
                         :height height}))]

     [:svg {:x x :y y :width width :height height}
      [:> "rect" props]
      (for [item childs]
        [:& shape-wrapper {:shape (translate-to-frame item shape) :key (:id item)}])])))

(defn- translate-to-frame
  [shape frame]
  (let [pt (gpt/point (- (:x frame)) (- (:y frame)))
        frame-ds-modifier (:displacement-modifier frame)
        rz-modifier (:resize-modifier shape)
        shape (cond-> shape
                (gmt/matrix? frame-ds-modifier)
                (geom/transform frame-ds-modifier)

                (and (= (:type shape) :group) (gmt/matrix? rz-modifier))
                (geom/transform rz-modifier)

                (and (not= (:type shape) :group) (gmt/matrix? rz-modifier))
                (-> (geom/transform rz-modifier)
                    (dissoc :resize-modifier)))]
    (geom/move shape pt)))
