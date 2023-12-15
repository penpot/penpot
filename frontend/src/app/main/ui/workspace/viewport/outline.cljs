;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.outline
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.container :as ctn]
   [app.main.refs :as refs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.attrs :as attrs]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [clojure.set :as set]
   [rumext.v2 :as mf]))

(mf/defc outline
  {::mf/wrap-props false}
  [props]
  (let [shape     (unchecked-get props "shape")
        modifier  (unchecked-get props "modifier")

        zoom      (d/nilv (unchecked-get props "zoom") 1)
        shape     (gsh/transform-shape shape (:modifiers modifier))
        transform (gsh/transform-str shape)

        ;; NOTE: that we don't use mf/deref to avoid a repaint dependency here
        objects   (deref refs/workspace-page-objects)
        color     (if (ctn/in-any-component? objects shape)
                    "var(--color-component-highlight)"
                    "var(--color-primary)")

        x         (dm/get-prop shape :x)
        y         (dm/get-prop shape :y)
        width     (dm/get-prop shape :width)
        height    (dm/get-prop shape :height)
        selrect   (dm/get-prop shape :selrect)
        type      (dm/get-prop shape :type)
        content   (get shape :content)
        path?     (cfh/path-shape? shape)

        path-data
        (mf/with-memo [path? content]
          (when (and ^boolean path? (some? content))
            (d/nilv (ex/ignoring (upf/format-path content)) "")))

        border-attrs
        (attrs/get-border-radius shape)

        outline-type
        (case type
          :circle "ellipse"
          :path "path"
          (if (some? (obj/get border-attrs "d"))
            "path"
            "rect"))

        props
        (obj/merge!
         #js {:fill "none"
              :stroke color
              :strokeWidth (/ 2 zoom)
              :pointerEvents "none"
              :transform transform}

         (case type
           :circle
           #js {:cx (+ x (/ width 2))
                :cy (+ y (/ height 2))
                :rx (/ width 2)
                :ry (/ height 2)}

           :path
           #js {:d path-data
                :transform nil}

           (let [x (dm/get-prop selrect :x)
                 y (dm/get-prop selrect :y)
                 w (dm/get-prop selrect :width)
                 h (dm/get-prop selrect :height)]
             #js {:x x
                  :y y
                  :width w
                  :height h
                  :rx (obj/get border-attrs "rx")
                  :ry (obj/get border-attrs "ry")
                  :d  (obj/get border-attrs "d")})))
        ]

    [:> outline-type props]))

(mf/defc shape-outlines-render
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % (mf/check-props ["shapes" "zoom" "modifiers"]))]}
  [props]
  (let [shapes    (unchecked-get props "shapes")
        zoom      (unchecked-get props "zoom")
        modifiers (unchecked-get props "modifiers")]

    (for [shape shapes]
      (let [shape-id (dm/get-prop shape :id)
            modifier (get modifiers shape-id)]
        [:& outline {:key (dm/str "outline-" shape-id)
                     :shape shape
                     :modifier modifier
                     :zoom zoom}]))))

(defn- show-outline?
  [shape]
  (and (not (:hidden shape))
       (not (:blocked shape))
       (not (:transforming shape))))

(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [selected    (or (obj/get props "selected") #{})
        hover       (or (obj/get props "hover") #{})
        highlighted (or (obj/get props "highlighted") #{})

        objects     (obj/get props "objects")
        edition     (obj/get props "edition")
        zoom        (obj/get props "zoom")
        modifiers   (obj/get props "modifiers")

        lookup      (d/getf objects)
        edition?    (fn [o] (= edition o))

        shapes      (-> #{}
                        (into (comp (remove edition?)
                                    (keep lookup)
                                    (filter show-outline?))
                              (set/union selected hover))
                        (into (comp (remove edition?)
                                    (keep lookup))
                              highlighted))

        modifiers (select-keys modifiers (map :id shapes))
        modifiers (hooks/use-equal-memo modifiers)
        shapes    (hooks/use-equal-memo shapes)]

    [:g.outlines
     [:& shape-outlines-render {:shapes shapes
                                :zoom zoom
                                :modifiers modifiers}]]))
