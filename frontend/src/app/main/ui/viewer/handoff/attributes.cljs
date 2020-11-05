;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes
  (:require
   [rumext.alpha :as mf]
   [app.util.i18n :as i18n]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.viewer.handoff.attributes.layout :refer [layout-panel]]
   [app.main.ui.viewer.handoff.attributes.fill :refer [fill-panel]]
   [app.main.ui.viewer.handoff.attributes.stroke :refer [stroke-panel]]
   [app.main.ui.viewer.handoff.attributes.shadow :refer [shadow-panel]]
   [app.main.ui.viewer.handoff.attributes.blur :refer [blur-panel]]
   [app.main.ui.viewer.handoff.attributes.image :refer [image-panel]]
   [app.main.ui.viewer.handoff.attributes.text :refer [text-panel]]))

(mf/defc attributes
  [{:keys [shapes frame options]}]
  (let [locale (mf/deref i18n/locale)
        shapes (->> shapes
                    (map #(gsh/translate-to-frame % frame)))

        shape (first shapes)]
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
         :locale locale}])]))

