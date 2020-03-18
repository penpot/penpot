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
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

(declare frame-wrapper)


(defn wrap-memo-shape
  ([component]
   (js/React.memo
    component
    (fn [np op]
      (let [n-shape (aget np "shape")
            o-shape (aget op "shape")]
        (= n-shape o-shape))))))

(mf/defc shape-wrapper
  {:wrap [wrap-memo-shape]}
  [{:keys [shape] :as props}]
  (when (and shape (not (:hidden shape)))
    (case (:type shape)
      :frame [:& frame-wrapper {:shape shape :childs []}]
      :curve [:& path/path-wrapper {:shape shape}]
      :text [:& text/text-wrapper {:shape shape}]
      :icon [:& icon/icon-wrapper {:shape shape}]
      :rect [:& rect/rect-wrapper {:shape shape}]
      :path [:& path/path-wrapper {:shape shape}]
      :image [:& image/image-wrapper {:shape shape}]
      :circle [:& circle/circle-wrapper {:shape shape}])))

(def frame-default-props
  {:fill-color "#ffffff"})

(declare frame-shape)
(declare translate-to-frame)

(def kaka [1 2 3])

(defn wrap-memo-frame
  ([component]
   (js/React.memo
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


(mf/defc frame-wrapper
  {:wrap [wrap-memo-frame]}
  [{:keys [shape objects] :as props}]
  (when (and shape (not (:hidden shape)))
    (let [selected-iref (mf/use-memo
                         {:fn #(refs/make-selected (:id shape))
                          :deps (mf/deps (:id shape))})
          selected? (mf/deref selected-iref)
          on-mouse-down #(common/on-mouse-down % shape)
          shape (merge frame-default-props shape)

          childs (mapv #(get objects %) (:shapes shape))

          on-double-click
          (fn [event]
            (dom/prevent-default event)
            (st/emit! dw/deselect-all
                      (dw/select-shape (:id shape))))]
      [:g {:class (when selected? "selected")
           :on-double-click on-double-click
           :on-mouse-down on-mouse-down}
       [:& frame-shape {:shape shape :childs childs}]])))

(mf/defc frame-shape
  [{:keys [shape childs] :as props}]
  (let [rotation    (:rotation shape)
        ds-modifier (:displacement-modifier shape)
        rz-modifier (:resize-modifier shape)

        shape (cond-> shape
                (gmt/matrix? rz-modifier) (geom/transform rz-modifier)
                (gmt/matrix? ds-modifier) (geom/transform ds-modifier))

        {:keys [id x y width height]} shape

        props (-> (attrs/extract-style-attrs shape)
                  (assoc :x 0
                         :y 0
                         :id (str "shape-" id)
                         :width width
                         :height height
                         ))

        translate #(translate-to-frame % ds-modifier (gpt/point (- x) (- y)))
        ]

    [:svg {:x x :y y :width width :height height}
     [:& "rect" props]
     (for [item childs]
       [:& shape-wrapper {:shape (translate item) :key (:id item)}])]))

(defn- translate-to-frame
  [shape frame-ds-modifier pt]
  (let [rz-modifier (:resize-modifier shape)
        shape (cond-> shape
                (gmt/matrix? frame-ds-modifier)
                (geom/transform frame-ds-modifier)

                (gmt/matrix? rz-modifier)
                (-> (geom/transform rz-modifier)
                    (dissoc :resize-modifier)))]
    (geom/move shape pt)))
