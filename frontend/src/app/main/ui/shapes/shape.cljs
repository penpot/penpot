;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.shape
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.common.geom.matrix :as gmt]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.custom-stroke :as cs]
   [app.main.ui.shapes.fill-image :as fim]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.gradients :as grad]
   [app.main.ui.shapes.svg-defs :as defs]
   [app.util.object :as obj]
   [rumext.alpha :as mf]
   [app.util.json :as json]))

(defn add-metadata
  "Adds as metadata properties that we cannot deduce from the exported SVG"
  [props shape]
  (let [add!
        (fn [props attr val]
          (let [ns-attr (str "penpot:" (-> attr d/name))]
            (-> props
                (obj/set! ns-attr val))))
        frame? (= :frame (:type shape))
        group? (= :group (:type shape))
        rect?  (= :text (:type shape))
        text?  (= :text (:type shape))
        mask?  (and group? (:masked-group? shape))]
    (-> props
        (add! :name              (-> shape :name))
        (add! :blocked           (-> shape (:blocked false) str))
        (add! :hidden            (-> shape (:hidden false) str))
        (add! :type              (-> shape :type d/name))

        (add! :stroke-style      (-> shape (:stroke-style :none) d/name))
        (add! :stroke-alignment  (-> shape (:stroke-alignment :center) d/name))

        (add! :transform         (-> shape (:transform (gmt/matrix)) str))
        (add! :transform-inverse (-> shape (:transform-inverse (gmt/matrix)) str))

        (cond-> (and rect? (some? (:r1 shape)))
          (-> (add! :r1 (-> shape (:r1 0) str))
              (add! :r2 (-> shape (:r2 0) str))
              (add! :r3 (-> shape (:r3 0) str))
              (add! :r4 (-> shape (:r4 0) str))))

        (cond-> text?
          (-> (add! :grow-type (-> shape :grow-type))
              (add! :content (-> shape :content json/encode))))

        (cond-> mask?
          (add! :masked-group "true"))

        (cond-> frame?
          (obj/set! "xmlns:penpot" "https://penpot.app/xmlns")))))

(mf/defc shape-container
  {::mf/forward-ref true
   ::mf/wrap-props false}
  [props ref]
  (let [shape          (obj/get props "shape")
        children       (obj/get props "children")
        pointer-events (obj/get props "pointer-events")
        render-id      (mf/use-memo #(str (uuid/next)))
        filter-id      (str "filter_" render-id)
        styles         (-> (obj/new)
                           (obj/set! "pointerEvents" pointer-events)

                           (cond-> (and (:blend-mode shape) (not= (:blend-mode shape) :normal))
                             (obj/set! "mixBlendMode" (d/name (:blend-mode shape)))))

        {:keys [x y width height type]} shape
        frame? (= :frame type)

        wrapper-props
        (-> (obj/clone props)
            (obj/without ["shape" "children"])
            (obj/set! "ref" ref)
            (obj/set! "id" (str "shape-" (:id shape)))
            (obj/set! "filter" (filters/filter-str filter-id shape))
            (obj/set! "style" styles)

            (cond-> frame?
              (-> (obj/set! "x" x)
                  (obj/set! "y" y)
                  (obj/set! "width" width)
                  (obj/set! "height" height)
                  (obj/set! "xmlnsXlink" "http://www.w3.org/1999/xlink")
                  (obj/set! "xmlns" "http://www.w3.org/2000/svg")))

            (add-metadata shape))

        wrapper-tag (if frame? "svg" "g")]

    [:& (mf/provider muc/render-ctx) {:value render-id}
     [:> wrapper-tag wrapper-props
      [:defs
       [:& defs/svg-defs          {:shape shape :render-id render-id}]
       [:& filters/filters        {:shape shape :filter-id filter-id}]
       [:& grad/gradient          {:shape shape :attr :fill-color-gradient}]
       [:& grad/gradient          {:shape shape :attr :stroke-color-gradient}]
       [:& fim/fill-image-pattern {:shape shape :render-id render-id}]
       [:& cs/stroke-defs         {:shape shape :render-id render-id}]]
      children]]))
