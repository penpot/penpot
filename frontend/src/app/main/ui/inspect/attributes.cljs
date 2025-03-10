;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.inspect.annotation :refer [annotation]]
   [app.main.ui.inspect.attributes.blur :refer [blur-panel]]
   [app.main.ui.inspect.attributes.fill :refer [fill-panel]]
   [app.main.ui.inspect.attributes.geometry :refer [geometry-panel]]
   [app.main.ui.inspect.attributes.image :refer [image-panel]]
   [app.main.ui.inspect.attributes.layout :refer [layout-panel]]
   [app.main.ui.inspect.attributes.layout-element :refer [layout-element-panel]]
   [app.main.ui.inspect.attributes.shadow :refer [shadow-panel]]
   [app.main.ui.inspect.attributes.stroke :refer [stroke-panel]]
   [app.main.ui.inspect.attributes.svg :refer [svg-panel]]
   [app.main.ui.inspect.attributes.text :refer [text-panel]]
   [app.main.ui.inspect.attributes.variant :refer [variant-panel*]]
   [app.main.ui.inspect.exports :refer [exports]]
   [rumext.v2 :as mf]))

(def type->options
  {:multiple [:fill :stroke :image :text :shadow :blur :layout-element]
   :frame    [:geometry :fill :stroke :shadow :blur :layout :layout-element]
   :group    [:geometry :svg :layout-element]
   :rect     [:geometry :fill :stroke :shadow :blur :svg :layout-element]
   :circle   [:geometry :fill :stroke :shadow :blur :svg :layout-element]
   :path     [:geometry :fill :stroke :shadow :blur :svg :layout-element]
   :image    [:image :geometry :fill :stroke :shadow :blur :svg :layout-element]
   :text     [:geometry :text :shadow :blur :stroke :layout-element]
   :variant  [:variant :geometry :fill :stroke :shadow :blur :layout :layout-element]})

(mf/defc attributes
  [{:keys [page-id file-id shapes frame from libraries share-id objects]}]
  (let [shapes             (hooks/use-equal-memo shapes)
        first-shape        (first shapes)
        data               (dm/get-in libraries [file-id :data])
        first-component    (ctkl/get-component data (:component-id first-shape))
        type               (cond
                             (and (= (count shapes) 1)
                                  (or (ctc/is-variant-container? first-shape)
                                      (ctc/is-variant? first-component)))
                             :variant

                             (= (count shapes) 1)
                             (:type first-shape)

                             :else
                             :multiple)
        options            (type->options type)
        annotation-content (when (= (count shapes) 1)
                             (ctkl/get-component-annotation first-shape libraries))]

    [:div {:class (stl/css-case :element-options true
                                :workspace-element-options (= from :workspace))}
     (for [[idx option] (map-indexed vector options)]
       [:> (case option
             :geometry         geometry-panel
             :layout           layout-panel
             :layout-element   layout-element-panel
             :fill             fill-panel
             :stroke           stroke-panel
             :shadow           shadow-panel
             :blur             blur-panel
             :image            image-panel
             :text             text-panel
             :svg              svg-panel
             :variant          variant-panel*)
        {:key idx
         :shapes shapes
         :objects objects
         :frame frame
         :from from
         :libraries libraries
         :file-id file-id}])
     (when annotation-content
       [:& annotation {:content annotation-content}])
     [:& exports
      {:shapes shapes
       :type type
       :page-id page-id
       :file-id file-id
       :share-id share-id}]]))
