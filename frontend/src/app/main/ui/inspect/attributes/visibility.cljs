;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.visibility
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def ^:private properties
  [:opacity
   :blend-mode
   :visibility])

(defn- has-visibility-props? [shape]
  (let [shape-type (:type shape)]
    (and
     (not (or (= shape-type :text) (= shape-type :group)))
     (or (:opacity shape)
         (:blend-mode shape)
         (:visibility shape)))))

(mf/defc visibility-block*
  [{:keys [objects shape]}]
  (for [property properties]
    (when-let [value (css/get-css-value objects shape property)]
      (let [property-name (cmm/get-css-rule-humanized property)]
        [:div {:class (stl/css :visibility-row)
               :key   (d/name property)}
         [:div {:title property-name
                :class (stl/css :global/attr-label)}
          property-name]
         [:div {:class (stl/css :global/attr-value)}

          [:> copy-button* {:data (css/get-css-property objects shape property)}
           [:div {:class (stl/css :button-children)} value]]]]))))

(mf/defc visibility-panel*
  [{:keys [objects shapes]}]
  (let [shapes (mf/with-memo [shapes]
                 (filter has-visibility-props? shapes))]

    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title "Visibility"
         :class (stl/css :title-spacing-visibility)}

        (when (= (count shapes) 1)
          [:> copy-button* {:data (css/get-shape-properties-css objects (first shapes) properties)
                            :class (stl/css :copy-btn-title)}])]

       (for [shape shapes]
         [:> visibility-block* {:shape shape
                                :objects objects
                                :key (:id shape)}])])))
