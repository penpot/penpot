;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.shape
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.main.ui.context :as muc]
   [app.main.ui.hooks :as h]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.export :as ed]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.svg-defs :as defs]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn propagate-wrapper-styles-child
  [child wrapper-props]
  (let [child-props-childs
        (-> (obj/get child "props")
            (obj/clone)
            (-> (obj/get "childs")))

        child-props-childs
        (->> child-props-childs
             (map #(assoc % :wrapper-styles (obj/get wrapper-props "style"))))

        child-props
        (-> (obj/get child "props")
            (obj/clone)
            (obj/set! "childs" child-props-childs))]

    (-> (obj/clone child)
        (obj/set! "props" child-props))))

(defn propagate-wrapper-styles
  ([children wrapper-props]
   (if (.isArray js/Array children)
     (->> children (map #(propagate-wrapper-styles-child % wrapper-props)))
     (-> children (propagate-wrapper-styles-child wrapper-props)))))

(mf/defc shape-container
  {::mf/forward-ref true
   ::mf/wrap-props false}
  [props ref]

  (let [shape            (unchecked-get props "shape")
        children         (unchecked-get props "children")
        pointer-events   (unchecked-get props "pointer-events")
        disable-shadows? (unchecked-get props "disable-shadows?")

        type             (:type shape)
        render-id        (h/use-id)
        filter-id        (dm/str "filter_" render-id)
        styles           (-> (obj/create)
                             (obj/set! "pointerEvents" pointer-events)
                             (cond-> (and (:blend-mode shape) (not= (:blend-mode shape) :normal))
                               (obj/set! "mixBlendMode" (d/name (:blend-mode shape)))))

        include-metadata? (mf/use-ctx ed/include-metadata-ctx)

        shape-without-blur (dissoc shape :blur)
        shape-without-shadows (assoc shape :shadow [])

        wrapper-props
        (-> (obj/clone props)
            (obj/without ["shape" "children" "disable-shadows?"])
            (obj/set! "ref" ref)
            (obj/set! "id" (dm/fmt "shape-%" (:id shape)))
            (obj/set! "style" styles))

        wrapper-props
        (cond-> wrapper-props
          (= :group type)
          (attrs/add-style-attrs shape render-id)

          (and (or (cph/group-shape? shape)
                   (cph/frame-shape? shape)
                   (cph/svg-raw-shape? shape))
               (not disable-shadows?))
          (obj/set! "filter" (filters/filter-str filter-id shape)))

        svg-group? (and (contains? shape :svg-attrs) (= :group type))

        children (cond-> children
                   svg-group?
                   (propagate-wrapper-styles wrapper-props))]

    [:& (mf/provider muc/render-id) {:value render-id}
     [:> :g wrapper-props
      (when include-metadata?
        [:& ed/export-data {:shape shape}])

      [:defs
       [:& defs/svg-defs          {:shape shape :render-id render-id}]
       [:& filters/filters        {:shape shape :filter-id filter-id}]
       [:& filters/filters        {:shape shape-without-blur :filter-id (dm/fmt "filter_shadow_%" render-id)}]
       [:& filters/filters        {:shape shape-without-shadows :filter-id (dm/fmt "filter_blur_%" render-id)}]
       [:& fills/fills            {:shape shape :render-id render-id}]
       [:& frame/frame-clip-def   {:shape shape :render-id render-id}]]
      children]]))
