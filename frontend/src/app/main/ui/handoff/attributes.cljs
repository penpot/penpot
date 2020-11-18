;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.handoff.attributes
  (:require
   [rumext.alpha :as mf]
   [app.util.i18n :as i18n]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.handoff.exports :refer [exports]]
   [app.main.ui.handoff.attributes.layout :refer [layout-panel]]
   [app.main.ui.handoff.attributes.fill :refer [fill-panel]]
   [app.main.ui.handoff.attributes.stroke :refer [stroke-panel]]
   [app.main.ui.handoff.attributes.shadow :refer [shadow-panel]]
   [app.main.ui.handoff.attributes.blur :refer [blur-panel]]
   [app.main.ui.handoff.attributes.image :refer [image-panel]]
   [app.main.ui.handoff.attributes.text :refer [text-panel]]))

(def type->options
  {:multiple [:fill :stroke :image :text :shadow :blur]
   :frame    [:layout :fill]
   :group    [:layout]
   :rect     [:layout :fill :stroke :shadow :blur]
   :circle   [:layout :fill :stroke :shadow :blur]
   :path     [:layout :fill :stroke :shadow :blur]
   :curve    [:layout :fill :stroke :shadow :blur]
   :image    [:image :layout :shadow :blur]
   :text     [:layout :text :shadow :blur]})

(mf/defc attributes
  [{:keys [page-id file-id shapes frame]}]
  (let [locale  (mf/deref i18n/locale)
        shapes  (->> shapes (map #(gsh/translate-to-frame % frame)))
        type    (if (= (count shapes) 1) (-> shapes first :type) :multiple)
        options (type->options type)]
    [:div.element-options
     (for [option options]
       [:> (case option
             :layout layout-panel
             :fill   fill-panel
             :stroke stroke-panel
             :shadow shadow-panel
             :blur   blur-panel
             :image  image-panel
             :text   text-panel)
        {:shapes shapes
         :frame frame
         :locale locale}])
     (when-not (= :multiple type)
       [:& exports
        {:shape (first shapes)
         :page-id page-id
         :file-id file-id}])]))
