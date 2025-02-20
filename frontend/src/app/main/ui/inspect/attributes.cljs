;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes
  (:require-macros [app.main.style :as stl])
  (:require
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
   :text     [:geometry :text :shadow :blur :stroke :layout-element]})

(mf/defc attributes
  [{:keys [page-id file-id shapes frame from libraries share-id objects]}]
  (let [shapes  (hooks/use-equal-memo shapes)
        type    (if (= (count shapes) 1) (-> shapes first :type) :multiple)
        options (type->options type)
        content (when (= (count shapes) 1)
                  (ctkl/get-component-annotation (first shapes) libraries))]

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
             :svg              svg-panel)
        {:key idx
         :shapes shapes
         :objects objects
         :frame frame
         :from from}])
     (when content
       [:& annotation {:content content}])
     [:& exports
      {:shapes shapes
       :type type
       :page-id page-id
       :file-id file-id
       :share-id share-id}]]))
