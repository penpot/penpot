;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.property-detail-copiable
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.inspect.common.colors :as isc]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:property-detail-copiable
  [:map
   [:detail :string]
   [:color {:optional true} :any] ;; color object with :color, :gradient or :image
   [:token {:optional true} :any] ;; resolved token object
   [:copied :boolean]
   [:on-click fn?]])

(mf/defc property-detail-copiable*
  {::mf/schema schema:property-detail-copiable}
  [{:keys [detail color token copied on-click]}]
  [:button {:class (stl/css-case :property-detail-copiable true
                                 :property-detail-copied copied
                                 :property-detail-copiable-color (some? color))
            :on-click on-click}
   (when color
     [:> bc/color-bullet {:color color
                          :mini true}])
   (if token
     [:span {:class (stl/css :property-detail-text :property-detail-text-token)}
      (:name token)]
     (if (:ref-id color)
       (let [colors-library (isc/use-colors-library color)

             file-colors-ref (mf/deref isc/file-colors-ref)
             file-colors-wokspace (mf/deref refs/workspace-file-colors)
             file-colors (or file-colors-ref file-colors-wokspace)

             color-library-name (get-in (or colors-library file-colors) [(:ref-id color) :name])
             color              (assoc color :name color-library-name)]
         [:span {:class (stl/css :property-detail-text)} (:name color)])
       [:span {:class (stl/css :property-detail-text)} detail]))
   [:> icon* {:class (stl/css :property-detail-icon)
              :icon-id (if copied i/tick i/clipboard)
              :size "s"
              :aria-label (tr "inspect.tabs.styles.panel.copy-to-clipboard")}]])


