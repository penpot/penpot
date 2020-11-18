;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.handoff.attributes.blur
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.i18n :refer [t]]
   [app.main.ui.icons :as i]
   [app.util.code-gen :as cg]
   [app.main.ui.components.copy-button :refer [copy-button]]))

(defn has-blur? [shape]
  (:blur shape))

(defn copy-data [shape]
  (cg/generate-css-props
   shape
   :blur
   {:to-prop "filter"
    :format #(str/fmt "blur(%spx)" (:value %))}))

(mf/defc blur-panel [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-blur?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.blur")]
        (when (= (count shapes) 1)
          [:& copy-button {:data (copy-data (first shapes))}])]

       (for [shape shapes]
         [:div.attributes-unit-row
          [:div.attributes-label (t locale "handoff.attributes.blur.value")]
          [:div.attributes-value (-> shape :blur :value) "px"]
          [:& copy-button {:data (copy-data shape)}]])])))
